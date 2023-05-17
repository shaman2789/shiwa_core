package org.shiwa

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.shiwa.cli.method._
import org.shiwa.ext.cats.effect._
import org.shiwa.ext.kryo._
import org.shiwa.http.p2p.P2PClient
import org.shiwa.infrastructure.trust.handler.trustHandler
import org.shiwa.modules._
import org.shiwa.schema.cluster.ClusterId
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshot}
import org.shiwa.sdk.app.{SDK, ShiwaIOApp}
import org.shiwa.sdk.domain.collateral.OwnCollateralNotSatisfied
import org.shiwa.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.shiwa.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.shiwa.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.shiwa.sdk.resources.MkHttpServer
import org.shiwa.sdk.resources.MkHttpServer.ServerName
import org.shiwa.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._

object Main
    extends ShiwaIOApp[Run](
      name = "dag-l0",
      header = "Shiwa Node",
      version = BuildInfo.version,
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf")
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = CoreKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    coreKryoRegistrar

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO](sdkQueues).asResource
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client)
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot, trustRatings).asResource
      services <- Services
        .make[IO](
          sdkServices,
          queues,
          storages,
          sdk.sdkValidators,
          sdkResources.client,
          sdkServices.session,
          sdk.seedlist,
          sdk.nodeId,
          keyPair,
          cfg
        )
        .asResource
      programs = Programs.make[IO](
        sdkPrograms,
        storages,
        services,
        keyPair,
        cfg,
        method.lastFullGlobalSnapshotOrdinal,
        p2pClient,
        services.snapshotContextFunctions
      )
      healthChecks <- HealthChecks
        .make[IO](
          storages,
          services,
          programs,
          p2pClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        trustHandler(storages.trust) <+> services.consensus.handler

      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks, nodeId, cfg)
        .asResource

      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair.getPrivate,
          cfg.environment,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        sdk.sdkValidators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      _ <- (method match {
        case _: RunValidator =>
          gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            programs.rollbackLoader.load(m.rollbackHash).flatMap {
              case (snapshotInfo, snapshot) =>
                storages.globalSnapshot
                  .prepend(snapshot, snapshotInfo) >>
                  services.consensus.manager.startFacilitatingAfterRollback(snapshot.ordinal, snapshot, snapshotInfo)
            }
          } >>
            services.collateral
              .hasCollateral(sdk.nodeId)
              .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap(_.toHashed[IO]).flatMap { hashedGenesis =>
                SnapshotLocalFileSystemStorage.make[IO, GlobalSnapshot](cfg.snapshot.snapshotPath).flatMap {
                  fullGlobalSnapshotLocalFileSystemStorage =>
                    fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                      GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                        Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                          signedFirstIncrementalSnapshot =>
                            storages.globalSnapshot.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info) >>
                              services.collateral
                                .hasCollateral(sdk.nodeId)
                                .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                              services.consensus.manager
                                .startFacilitatingAfterRollback(
                                  signedFirstIncrementalSnapshot.ordinal,
                                  signedFirstIncrementalSnapshot,
                                  hashedGenesis.info
                                )

                        }
                      }
                }
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
      }).asResource
    } yield ()
  }
}

/* Описание того, что делает данный код:

1. Импортируются необходимые зависимости и классы.
2. Объявляется объект `Main`, который расширяет класс `ShiwaIOApp[Run]`. `ShiwaIOApp` является основным классом
фреймворка и предоставляет функциональность для запуска приложения.
3. В объекте `Main` определены несколько типов и констант:
   - `type KryoRegistrationIdRange = CoreKryoRegistrationIdRange`: Определяет псевдоним для типа `CoreKryoRegistrationIdRange`.
   - `val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]]`: Определяет карту регистрации Kryo для классов.
4. Определен метод `run`, который принимает параметры `method: Run` и `sdk: SDK[IO]` и возвращает ресурс типа `Resource[IO, Unit]`.
5. В методе `run` происходит инициализация различных компонентов и сервисов:
   - Создаются очереди (`queues`) с помощью `Queues.make`.
   - Создается клиент P2P (`p2pClient`) с помощью `P2PClient.make`.
   - Создаются хранилища (`storages`) с помощью `Storages.make`.
   - Создаются сервисы (`services`) с помощью `Services.make`.
   - Создаются программы (`programs`) с помощью `Programs.make`.
   - Создаются health checks (`healthChecks`) с помощью `HealthChecks.make`.
   - Создается обработчик слухов (`rumorHandler`) с помощью `RumorHandlers.make`.
   - Запускаются демоны (`Daemons.start`) для обработки различных задач.
   - Создается API-интерфейс (`api`) с помощью `HttpApi.make`.
   - Создаются HTTP-серверы (`MkHttpServer`) для обслуживания различных точек доступа.
   - Создается демон Gossip (`gossipDaemon`) с помощью `GossipDaemon.make`.
   - В зависимости от значения `method` выполняются различные действия, связанные с запуском, откатом или генезисом.
6. В конце метода `run` возвращается пустое значение (`Unit`).
7. Основной код приложения заключается в теле метода `run`. Этот код выполняется при запуске приложения и содержит логику для создания
и конфигурации различных компонентов, установки соединений, выполнения задач и запуска серверов.
8. Объект `Main` является точкой входа, и его методы будут вызываться при запуске приложения.*/
