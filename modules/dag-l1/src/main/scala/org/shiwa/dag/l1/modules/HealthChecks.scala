package org.shiwa.dag.l1.modules

import cats.effect.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.shiwa.dag.l1.http.p2p.P2PClient
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.config.types.HealthCheckConfig
import org.shiwa.sdk.domain.cluster.services.Session
import org.shiwa.sdk.effects.GenUUID
import org.shiwa.sdk.infrastructure.healthcheck.ping.{PingHealthCheckConsensus, PingHealthCheckConsensusDriver}

import org.http4s.client.Client

object HealthChecks {

  def make[
    F[_]: Async: KryoSerializer: GenUUID: Random: Supervisor,
    T <: Transaction,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, T, B, P, S, SI],
    services: Services[F, T, B, P, S, SI],
    programs: Programs[F, T, B, P, S, SI],
    p2pClient: P2PClient[F, T, B],
    client: Client[F],
    session: Session[F],
    config: HealthCheckConfig,
    selfId: PeerId
  ): F[HealthChecks[F]] = {
    def ping = PingHealthCheckConsensus.make(
      storages.cluster,
      programs.joining,
      selfId,
      new PingHealthCheckConsensusDriver(),
      config,
      services.gossip,
      p2pClient.node,
      client,
      session
    )

    ping.map {
      new HealthChecks(_) {}
    }
  }
}

sealed abstract class HealthChecks[F[_]] private (
  val ping: PingHealthCheckConsensus[F]
)
