package org.shiwa.sdk.domain.snapshot.storage

import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.height.Height
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo}
import org.shiwa.security.Hashed

trait LastSnapshotStorage[F[_], S <: Snapshot[_, _], SI <: SnapshotInfo[_]] {
  def set(snapshot: Hashed[S], state: SI): F[Unit]
  def setInitial(snapshot: Hashed[S], state: SI): F[Unit]
  def get: F[Option[Hashed[S]]]
  def getCombined: F[Option[(Hashed[S], SI)]]
  def getOrdinal: F[Option[SnapshotOrdinal]]
  def getHeight: F[Option[Height]]
}
