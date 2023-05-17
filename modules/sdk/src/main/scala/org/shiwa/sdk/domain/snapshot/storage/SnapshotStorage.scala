package org.shiwa.sdk.domain.snapshot.storage

import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.snapshot.Snapshot
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed

trait SnapshotStorage[F[_], S <: Snapshot[_, _], State] {

  def prepend(snapshot: Signed[S], state: State): F[Boolean]

  def head: F[Option[(Signed[S], State)]]
  def headSnapshot: F[Option[Signed[S]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]]

  def get(hash: Hash): F[Option[Signed[S]]]

}
