package org.shiwa.currency.l1.modules

import cats.Order
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.dag.l1.domain.block.BlockStorage
import org.shiwa.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.shiwa.dag.l1.domain.transaction.TransactionStorage
import org.shiwa.dag.l1.infrastructure.address.storage.AddressStorage
import org.shiwa.dag.l1.modules.{Storages => BaseStorages}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.peer.L0Peer
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.schema.{Block, GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.shiwa.sdk.domain.cluster.storage.L0ClusterStorage
import org.shiwa.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.shiwa.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.shiwa.sdk.infrastructure.snapshot.storage.LastSnapshotStorage
import org.shiwa.sdk.modules.SdkStorages

object Storages {

  def make[
    F[_]: Async: Random: KryoSerializer,
    T <: Transaction: Order: Ordering,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    sdkStorages: SdkStorages[F],
    l0Peer: L0Peer,
    globalL0Peer: L0Peer
  ): F[Storages[F, T, B, P, S, SI]] =
    for {
      blockStorage <- BlockStorage.make[F, B]
      consensusStorage <- ConsensusStorage.make[F, T, B]
      l0ClusterStorage <- L0ClusterStorage.make[F](l0Peer)
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastCurrencySnapshotStorage <- LastSnapshotStorage.make[F, S, SI]
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      transactionStorage <- TransactionStorage.make[F, T]
      addressStorage <- AddressStorage.make[F]
    } yield
      new Storages[F, T, B, P, S, SI] {
        val address = addressStorage
        val block = blockStorage
        val consensus = consensusStorage
        val cluster = sdkStorages.cluster
        val l0Cluster = l0ClusterStorage
        val globalL0Cluster = globalL0ClusterStorage
        val lastSnapshot = lastCurrencySnapshotStorage
        val lastGlobalSnapshot = lastGlobalSnapshotStorage
        val node = sdkStorages.node
        val session = sdkStorages.session
        val rumor = sdkStorages.rumor
        val transaction = transactionStorage
      }
}

sealed abstract class Storages[
  F[_],
  T <: Transaction: Order: Ordering,
  B <: Block[T],
  P <: StateProof,
  S <: Snapshot[T, B],
  SI <: SnapshotInfo[P]
] extends BaseStorages[F, T, B, P, S, SI] {

  val globalL0Cluster: L0ClusterStorage[F]
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
}
