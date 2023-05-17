package org.shiwa.sdk.domain.cluster.services

import org.shiwa.schema.cluster.{SessionToken, TokenVerificationResult}
import org.shiwa.schema.peer.PeerId

trait Session[F[_]] {
  def createSession: F[SessionToken]
  def verifyToken(peer: PeerId, headerToken: Option[SessionToken]): F[TokenVerificationResult]
}
