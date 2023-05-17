package org.shiwa.currency.l0.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.shiwa.currency.l0.snapshot.services.StateChannelSnapshotService
import org.shiwa.currency.schema.currency._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.config.types.SnapshotConfig
import org.shiwa.sdk.domain.cluster.services.Session
import org.shiwa.sdk.domain.cluster.storage.ClusterStorage
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.rewards.Rewards
import org.shiwa.sdk.infrastructure.consensus.Consensus
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot.{CurrencySnapshotAcceptanceManager, SnapshotConsensus}
import org.shiwa.security.SecurityProvider

import org.http4s.client.Client

object CurrencySnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    rewards: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot],
    snapshotConfig: SnapshotConfig,
    client: Client[F],
    session: Session[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F]
  ): F[SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]] =
    Consensus.make[F, CurrencySnapshotEvent, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext](
      CurrencySnapshotConsensusFunctions.make[F](
        stateChannelSnapshotService,
        snapshotAcceptanceManager,
        collateral,
        rewards
      ),
      gossip,
      selfId,
      keyPair,
      snapshotConfig.consensus,
      seedlist,
      clusterStorage,
      nodeStorage,
      client,
      session
    )

}
