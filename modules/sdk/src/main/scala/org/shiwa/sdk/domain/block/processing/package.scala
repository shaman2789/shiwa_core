package org.shiwa.sdk.domain.block

import org.shiwa.schema.address.Address
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.transaction.TransactionChainValidator.TransactionNel

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

package object processing {
  type UsageCount = NonNegLong
  type TxChains[T <: Transaction] = Map[Address, TransactionNel[T]]

  val usageIncrement: NonNegLong = 1L
  val initUsageCount: NonNegLong = 0L
  val deprecationThreshold: NonNegLong = 2L
}