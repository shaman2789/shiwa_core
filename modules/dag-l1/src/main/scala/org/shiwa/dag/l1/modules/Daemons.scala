package org.shiwa.dag.l1.modules

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._
import cats.syntax.traverse._

import org.shiwa.dag.l1.infrastructure.healthcheck.HealthCheckDaemon
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.infrastructure.cluster.daemon.NodeStateDaemon
import org.shiwa.sdk.infrastructure.collateral.daemon.CollateralDaemon
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.security.SecurityProvider

object Daemons {

  def start[
    F[_]: Async: SecurityProvider: KryoSerializer: Random: Parallel: Metrics: Supervisor,
    T <: Transaction,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, T, B, P, S, SI],
    services: Services[F, T, B, P, S, SI],
    healthChecks: HealthChecks[F]
  ): F[Unit] =
    List[Daemon[F]](
      NodeStateDaemon.make(storages.node, services.gossip),
      CollateralDaemon.make(services.collateral, storages.lastSnapshot, storages.cluster),
      HealthCheckDaemon.make(healthChecks)
    ).traverse(_.start).void

}