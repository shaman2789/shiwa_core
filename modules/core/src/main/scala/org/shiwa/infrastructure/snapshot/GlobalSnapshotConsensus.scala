package org.shiwa.infrastructure.snapshot

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotStateProof}
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types.SnapshotConfig
import org.shiwa.sdk.domain.cluster.services.Session
import org.shiwa.sdk.domain.cluster.storage.ClusterStorage
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.rewards.Rewards
import org.shiwa.sdk.domain.snapshot.storage.SnapshotStorage
import org.shiwa.sdk.infrastructure.consensus.Consensus
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot.GlobalSnapshotAcceptanceManager
import org.shiwa.security.SecurityProvider

import io.circe.disjunctionCodecs._
import org.http4s.client.Client

object GlobalSnapshotConsensus {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    gossip: Gossip[F],
    selfId: PeerId,
    keyPair: KeyPair,
    seedlist: Option[Set[PeerId]],
    collateral: Amount,
    clusterStorage: ClusterStorage[F],
    nodeStorage: NodeStorage[F],
    globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F],
    snapshotConfig: SnapshotConfig,
    environment: AppEnvironment,
    client: Client[F],
    session: Session[F],
    rewards: Rewards[F, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot]
  ): F[Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext]] =
    Consensus.make[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext](
      GlobalSnapshotConsensusFunctions.make[F](
        globalSnapshotStorage,
        snapshotAcceptanceManager,
        collateral,
        rewards,
        environment
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
