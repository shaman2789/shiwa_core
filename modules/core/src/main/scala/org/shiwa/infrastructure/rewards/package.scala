package org.shiwa.infrastructure

import cats.data.StateT

import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Amount

package object rewards {

  type DistributionState[F[_]] = StateT[F, Amount, List[(Address, Amount)]]

}
