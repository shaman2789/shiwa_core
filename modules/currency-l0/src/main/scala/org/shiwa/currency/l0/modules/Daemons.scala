package org.shiwa.currency.l0.modules

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._
import cats.syntax.traverse._

import org.shiwa.currency.l0.snapshot.CurrencySnapshotEventsPublisherDaemon
import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.domain.healthcheck.HealthChecks
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
    healthChecks: HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      DownloadDaemon.make(storages.node, programs.download),
      HealthCheckDaemon.make(healthChecks),
      CurrencySnapshotEventsPublisherDaemon.make(queues.l1Output, services.gossip),
      CollateralDaemon.make(services.collateral, storages.snapshot, storages.cluster)
    ).traverse(_.start).void

}
