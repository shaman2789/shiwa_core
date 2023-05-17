package org.shiwa.dag.l1.modules

import java.security.PrivateKey

import cats.Show
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.semigroupk._

import org.shiwa.dag.l1.http.Routes
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.config.types.HttpConfig
import org.shiwa.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.shiwa.sdk.http.routes._
import org.shiwa.sdk.infrastructure.healthcheck.ping.PingHealthCheckRoutes
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.security.SecurityProvider

import io.circe.{Decoder, Encoder}
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.Router
import org.http4s.server.middleware.{CORS, RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider: Metrics: Supervisor,
    T <: Transaction: Decoder: Encoder: Show,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, T, B, P, S, SI],
    queues: Queues[F, T, B],
    privateKey: PrivateKey,
    services: Services[F, T, B, P, S, SI],
    programs: Programs[F, T, B, P, S, SI],
    healthchecks: HealthChecks[F],
    selfId: PeerId,
    nodeVersion: String,
    httpCfg: HttpConfig
  ): HttpApi[F, T, B, P, S, SI] =
    new HttpApi[F, T, B, P, S, SI](storages, queues, privateKey, services, programs, healthchecks, selfId, nodeVersion, httpCfg) {}
}

sealed abstract class HttpApi[
  F[_]: Async: KryoSerializer: SecurityProvider: Metrics: Supervisor,
  T <: Transaction: Decoder: Encoder: Show,
  B <: Block[T],
  P <: StateProof,
  S <: Snapshot[T, B],
  SI <: SnapshotInfo[P]
] private (
  storages: Storages[F, T, B, P, S, SI],
  queues: Queues[F, T, B],
  privateKey: PrivateKey,
  services: Services[F, T, B, P, S, SI],
  programs: Programs[F, T, B, P, S, SI],
  healthchecks: HealthChecks[F],
  selfId: PeerId,
  nodeVersion: String,
  httpCfg: HttpConfig
) {
  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster, services.collateral)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, services.gossip)
  private val dagRoutes = Routes[F, T](services.transaction, storages.transaction, storages.l0Cluster, queues.peerBlockConsensusInput)
  private val nodeRoutes = NodeRoutes[F](storages.node, storages.session, storages.cluster, nodeVersion, httpCfg, selfId)
  private val healthcheckP2PRoutes = {
    val pingHealthcheckRoutes = PingHealthCheckRoutes[F](healthchecks.ping)

    Router("healthcheck" -> pingHealthcheckRoutes.p2pRoutes)
  }

  private val metricRoutes = MetricRoutes[F]().routes
  private val targetRoutes = TargetRoutes[F](services.cluster).routes

  private val openRoutes: HttpRoutes[F] =
    CORS.policy.withAllowOriginAll.withAllowHeadersAll.withAllowCredentials(false).apply {
      `X-Id-Middleware`.responseMiddleware(selfId) {
        dagRoutes.publicRoutes <+>
          clusterRoutes.publicRoutes <+>
          nodeRoutes.publicRoutes <+>
          metricRoutes <+>
          targetRoutes
      }
    }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session, selfId)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            clusterRoutes.p2pRoutes <+>
              nodeRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              dagRoutes.p2pRoutes <+>
              healthcheckP2PRoutes
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = { http: HttpApp[F] =>
    RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
  }.andThen { http: HttpApp[F] =>
    ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}