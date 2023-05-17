package org.shiwa.sdk.domain.cluster.storage

import org.shiwa.schema.cluster.SessionToken

trait SessionStorage[F[_]] {
  def createToken: F[SessionToken]
  def getToken: F[Option[SessionToken]]
  def clearToken: F[Unit]
}
