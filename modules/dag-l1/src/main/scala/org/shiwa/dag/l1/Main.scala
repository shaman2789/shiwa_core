package org.shiwa.dag.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.shiwa.BuildInfo
import org.shiwa.dag.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.shiwa.dag.l1.domain.snapshot.programs.SHISnapshotProcessor
import org.shiwa.dag.l1.http.p2p.P2PClient
import org.shiwa.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.shiwa.dag.l1.modules._
import org.shiwa.ext.cats.effect.ResourceIO
import org.shiwa.ext.kryo._
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.cluster.ClusterId
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.node.NodeState.SessionStarted
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.shiwa.sdk.app.{SDK, ShiwaIOApp}
import org.shiwa.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.shiwa.sdk.resources.MkHttpServer
import org.shiwa.sdk.resources.MkHttpServer.ServerName
import org.shiwa.shared.{SharedKryoRegistrationIdRange, sharedKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or

object Main
    extends ShiwaIOApp[Run](
      "dag-l1",
      "SHI L1 node",
      ClusterId("17e78993-37ea-4539-a4f3-039068ea1e92"),
      version = BuildInfo.version
    ) {
  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = DagL1KryoRegistrationIdRange Or SharedKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL1KryoRegistrar.union(sharedKryoRegistrar)

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO, SHITransaction, SHIBlock](sdkQueues).asResource
      storages <- Storages
        .make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          sdkStorages,
          method.l0Peer
        )
        .asResource
      validators = Validators
        .make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages, seedlist)
      p2pClient = P2PClient.make[IO, SHITransaction, SHIBlock](
        sdkP2PClient,
        sdkResources.client,
        currencyPathPrefix = "dag"
      )
      services = Services.make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        storages,
        storages.lastSnapshot,
        storages.l0Cluster,
        validators,
        sdkServices,
        p2pClient,
        cfg
      )
      snapshotProcessor = SHISnapshotProcessor.make(
        storages.address,
        storages.block,
        storages.lastSnapshot,
        storages.transaction,
        sdkServices.globalSnapshotContextFns
      )
      programs = Programs.make(sdkPrograms, p2pClient, storages, snapshotProcessor)
      healthChecks <- HealthChecks
        .make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
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
        blockRumorHandler(queues.peerBlock)

      _ <- Daemons
        .start(storages, services, healthChecks)
        .asResource

      api = HttpApi
        .make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          healthChecks,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          storages,
          validators
        )
        .asResource

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )
      _ <- {
        method match {
          case cfg: RunInitialValidator =>
            gossipDaemon.startAsInitialValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              services.cluster.createSession >>
              services.session.createSession >>
              storages.node.tryModifyState(SessionStarted, NodeState.Ready)

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        }
      }.asResource
      _ <- stateChannel.runtime.compile.drain.handleErrorWith { error =>
        logger.error(error)("An error occured during state channel runtime") >> error.raiseError[IO, Unit]
      }.asResource
    } yield ()
  }
}
