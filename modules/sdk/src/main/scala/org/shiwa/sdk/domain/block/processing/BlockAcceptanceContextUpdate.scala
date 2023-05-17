package org.shiwa.sdk.domain.block.processing

import org.shiwa.schema.BlockReference
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.transaction.TransactionReference

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(eqv, show)
case class BlockAcceptanceContextUpdate(
  balances: Map[Address, Balance],
  lastTxRefs: Map[Address, TransactionReference],
  parentUsages: Map[BlockReference, NonNegLong]
)

object BlockAcceptanceContextUpdate {

  val empty: BlockAcceptanceContextUpdate = BlockAcceptanceContextUpdate(
    Map.empty,
    Map.empty,
    Map.empty
  )
}
