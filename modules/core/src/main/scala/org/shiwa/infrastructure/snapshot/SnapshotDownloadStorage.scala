package org.shiwa.infrastructure.snapshot

import cats.FlatMap
import cats.syntax.flatMap._

import org.shiwa.domain.snapshot.storages.SnapshotDownloadStorage
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.shiwa.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed

object SnapshotDownloadStorage {
  def make[F[_]: FlatMap](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot]
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)
      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = tmpStorage.writeUnderOrdinal(snapshot)
      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = persistedStorage.write(snapshot)

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def movePersistedToTmp(hash: Hash): F[Unit] = tmpStorage.getPath(hash).flatMap(persistedStorage.move(hash, _))
      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)
    }
}