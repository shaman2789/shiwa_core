package org.shiwa.sdk.domain.snapshot

import org.shiwa.schema.peer.Peer

trait PeerSelect[F[_]] {
  def select: F[Peer]
}

object PeerSelect {
  val peerSelectLoggerName = "PeerSelectLogger"
}
