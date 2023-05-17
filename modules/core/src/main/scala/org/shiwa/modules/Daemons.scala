package org.shiwa.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.shiwa.config.types.AppConfig
import org.shiwa.infrastructure.snapshot.GlobalSnapshotEventsPublisherDaemon
import org.shiwa.infrastructure.trust.TrustDaemon
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.shiwa.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.shiwa.sdk.infrastructure.healthcheck.daemon.HealthCheckDaemon
import org.shiwa.sdk.infrastructure.snapshot.daemon.DownloadDaemon

object Daemons {

  def start[F[_]: Async: Supervisor](
    storages: Storages[F],
    services: Services[F],
    programs: Programs[F],
    queues: Queues[F],
    healthChecks: HealthChecks[F],
    nodeId: PeerId,
    cfg: AppConfig
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      TrustDaemon.make(cfg.trust.daemon, storages.trust, nodeId),
      HealthCheckDaemon.make(healthChecks),
      GlobalSnapshotEventsPublisherDaemon.make(queues.stateChannelOutput, queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.globalSnapshot, storages.cluster)
    ).traverse(_.start).void

}
