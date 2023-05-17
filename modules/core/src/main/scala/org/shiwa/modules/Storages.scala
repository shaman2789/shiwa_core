package org.shiwa.modules

import cats.effect.kernel.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.domain.snapshot.storages.SnapshotDownloadStorage
import org.shiwa.domain.trust.storage.TrustStorage
import org.shiwa.infrastructure.snapshot.SnapshotDownloadStorage
import org.shiwa.infrastructure.trust.storage.TrustStorage
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, GlobalSnapshotInfo}
import org.shiwa.sdk.config.types.SnapshotConfig
import org.shiwa.sdk.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.shiwa.sdk.domain.collateral.LatestBalances
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.snapshot.storage.SnapshotStorage
import org.shiwa.sdk.infrastructure.gossip.RumorStorage
import org.shiwa.sdk.infrastructure.snapshot.storage.{SnapshotLocalFileSystemStorage, SnapshotStorage}
import org.shiwa.sdk.modules.SdkStorages

object Storages {

  def make[F[_]: Async: KryoSerializer: Supervisor](
    sdkStorages: SdkStorages[F],
    snapshotConfig: SnapshotConfig,
    trustUpdates: Option[PeerObservationAdjustmentUpdateBatch]
  ): F[Storages[F]] =
    for {
      trustStorage <- TrustStorage.make[F](trustUpdates)
      incrementalGlobalSnapshotTmpLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalTmpSnapshotPath
      )
      incrementalGlobalSnapshotPersistedLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalIncrementalSnapshot](
        snapshotConfig.incrementalPersistedSnapshotPath
      )
      fullGlobalSnapshotLocalFileSystemStorage <- SnapshotLocalFileSystemStorage.make[F, GlobalSnapshot](
        snapshotConfig.snapshotPath
      )
      globalSnapshotStorage <- SnapshotStorage.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        snapshotConfig.inMemoryCapacity
      )
      snapshotDownloadStorage = SnapshotDownloadStorage
        .make[F](
          incrementalGlobalSnapshotTmpLocalFileSystemStorage,
          incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
          fullGlobalSnapshotLocalFileSystemStorage
        )
    } yield
      new Storages[F](
        cluster = sdkStorages.cluster,
        node = sdkStorages.node,
        session = sdkStorages.session,
        rumor = sdkStorages.rumor,
        trust = trustStorage,
        globalSnapshot = globalSnapshotStorage,
        fullGlobalSnapshot = fullGlobalSnapshotLocalFileSystemStorage,
        incrementalGlobalSnapshotLocalFileSystemStorage = incrementalGlobalSnapshotPersistedLocalFileSystemStorage,
        snapshotDownload = snapshotDownloadStorage
      ) {}
}

sealed abstract class Storages[F[_]] private (
  val cluster: ClusterStorage[F],
  val node: NodeStorage[F],
  val session: SessionStorage[F],
  val rumor: RumorStorage[F],
  val trust: TrustStorage[F],
  val globalSnapshot: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] with LatestBalances[F],
  val fullGlobalSnapshot: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  val incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
  val snapshotDownload: SnapshotDownloadStorage[F]
)
