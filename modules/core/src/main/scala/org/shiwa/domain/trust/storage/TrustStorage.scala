package org.shiwa.domain.trust.storage

import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.trust.{PeerObservationAdjustmentUpdateBatch, PublicTrust, TrustInfo}

trait TrustStorage[F[_]] {
  def updateTrust(trustUpdates: PeerObservationAdjustmentUpdateBatch): F[Unit]
  def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit]
  def getTrust: F[Map[PeerId, TrustInfo]]
  def getPublicTrust: F[PublicTrust]
  def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit]
}
