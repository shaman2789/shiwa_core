package org.shiwa.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.option._
import cats.syntax.semigroupk._

import org.shiwa.domain.cell.{L0Cell, L0CellInput}
import org.shiwa.http.routes._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.AppEnvironment.{Dev, Mainnet, Testnet}
import org.shiwa.sdk.config.types.HttpConfig
import org.shiwa.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.shiwa.sdk.http.routes
import org.shiwa.sdk.http.routes._
import org.shiwa.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.Signed

import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    healthchecks: HealthChecks[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId,
    nodeVersion: String,
    httpCfg: HttpConfig
  ): HttpApi[F] =
    new HttpApi[F](
      storages,
      queues,
      services,
      programs,
      healthchecks,
      privateKey,
      environment,
      selfId,
      nodeVersion,
      httpCfg
    ) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer: Metrics] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  healthchecks: HealthChecks[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig
) {

  private val mkDagCell = (block: Signed[SHIBlock]) =>
    L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput).apply(L0CellInput.HandleSHIL1(block))

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)

  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes = StateChannelRoutes[F](services.stateChannel)
  private val snapshotRoutes =
    SnapshotRoutes[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
      storages.globalSnapshot,
      storages.fullGlobalSnapshot.some,
      "/global-snapshots"
    )
  private val dagRoutes = CurrencyRoutes[F, SHITransaction, SHIBlock, GlobalIncrementalSnapshot]("/dag", services.address, mkDagCell)
  private val consensusInfoRoutes = new ConsensusInfoRoutes[F, SnapshotOrdinal](services.cluster, services.consensus.storage, selfId)
  private val consensusRoutes = services.consensus.routes.p2pRoutes

  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val debugRoutes = DebugRoutes[F](
    storages.cluster,
    services.consensus,
    services.gossip,
    services.session
  ).routes

  private val metricRoutes = routes.MetricRoutes[F]().routes
  private val targetRoutes = routes.TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      PeerAuthMiddleware
        .responseSignerMiddleware(privateKey, storages.session, selfId) {
          `X-Id-Middleware`.responseMiddleware(selfId) {
            (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
              metricRoutes <+>
              targetRoutes <+>
              (if (environment == Mainnet) HttpRoutes.empty else stateChannelRoutes.publicRoutes) <+>
              clusterRoutes.publicRoutes <+>
              snapshotRoutes.publicRoutes <+>
              dagRoutes.publicRoutes <+>
              nodeRoutes.publicRoutes <+>
              consensusInfoRoutes.publicRoutes
          }
        }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            PeerAuthMiddleware.requestCollateralVerifierMiddleware(services.collateral)(
              clusterRoutes.p2pRoutes <+>
                nodeRoutes.p2pRoutes <+>
                gossipRoutes.p2pRoutes <+>
                trustRoutes.p2pRoutes <+>
                snapshotRoutes.p2pRoutes <+>
                healthcheckP2PRoutes <+>
                consensusRoutes
            )
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes <+>
      trustRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}