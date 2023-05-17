package org.shiwa.currency.l0.modules

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.shiwa.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.peer.L0Peer
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.shiwa.sdk.config.types.SnapshotConfig
import org.shiwa.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage, SessionStorage}
import org.shiwa.sdk.domain.collateral.LatestBalances
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.shiwa.sdk.infrastructure.cluster.storage.L0ClusterStorage
import org.shiwa.sdk.infrastructure.gossip.RumorStorage
import org.shiwa.sdk.infrastructure.snapshot.storage.{LastSnapshotStorage, SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.shiwa.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor: Random](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig,
    globalL0Peer: L0Peer
  ): F[Storages[F]] =
    for {
      snapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, CurrencyIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      snapshotStorage <- SnapshotStorage
        .make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](snapshotLocalFileSystemStorage, snapshotConfig.inMemoryCapacity)
      lastGlobalSnapshotStorage <- LastSnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
      globalL0ClusterStorage <- L0ClusterStorage.make[F](globalL0Peer)
      lastSignedBinaryHashStorage <- LastSignedBinaryHashStorage.make[F]
    } yield
      new Storages[F](
        globalL0Cluster = globalL0ClusterStorage,
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        lastSignedBinaryHash = lastSignedBinaryHashStorage,
        snapshot = snapshotStorage,
        lastGlobalSnapshot = lastGlobalSnapshotStorage,
        incrementalSnapshotLocalFileSystemStorage = snapshotLocalFileSystemStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val globalL0Cluster: L0ClusterStorage[F],
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val lastSignedBinaryHash: LastSignedBinaryHashStorage[F],
  val snapshot: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] with LatestBalances[F],
  val lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  val incrementalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot]
)
