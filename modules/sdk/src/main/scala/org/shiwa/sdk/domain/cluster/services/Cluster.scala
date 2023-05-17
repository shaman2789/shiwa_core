package org.shiwa.sdk.domain.cluster.services

import org.shiwa.schema.cluster.ClusterSessionToken
import org.shiwa.schema.peer._
import org.shiwa.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info: F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
