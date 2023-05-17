package org.shiwa.sdk.domain.collateral

import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance

import fs2.Stream

trait LatestBalances[F[_]] {
  def getLatestBalances: F[Option[Map[Address, Balance]]]
  def getLatestBalancesStream: Stream[F, Map[Address, Balance]]
}
