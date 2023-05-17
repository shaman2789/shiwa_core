package org.shiwa.sdk.domain.http.p2p

import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.snapshot.Snapshot
import org.shiwa.sdk.http.p2p.PeerResponse.PeerResponse
import org.shiwa.security.signature.Signed

trait SnapshotClient[F[_], S <: Snapshot[_, _]] {
  def getLatestOrdinal: PeerResponse[F, SnapshotOrdinal]
  def get(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[S]]
}
