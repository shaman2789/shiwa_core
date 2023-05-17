package org.shiwa.sdk.domain.collateral

import cats.Functor
import cats.syntax.functor._

import org.shiwa.schema.peer.PeerId

trait Collateral[F[_]] {
  def hasCollateral(peerId: PeerId): F[Boolean]
  def hasNotCollateral(peerId: PeerId)(implicit F: Functor[F]): F[Boolean] = hasCollateral(peerId).map(!_)
}
