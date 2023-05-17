package org.shiwa.sdk.domain.block.processing

import cats.data.EitherT

import org.shiwa.schema.Block
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.block.processing.{TxChains, UsageCount}
import org.shiwa.security.signature.Signed

trait BlockAcceptanceLogic[F[_], T <: Transaction, B <: Block[T]] {
  def acceptBlock(
    block: Signed[B],
    txChains: TxChains[T],
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}
