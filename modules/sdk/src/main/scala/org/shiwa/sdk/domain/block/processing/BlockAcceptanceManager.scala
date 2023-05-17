package org.shiwa.sdk.domain.block.processing

import org.shiwa.schema.Block
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.block.processing.UsageCount
import org.shiwa.security.signature.Signed

trait BlockAcceptanceManager[F[_], T <: Transaction, B <: Block[T]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[B]],
    context: BlockAcceptanceContext[F]
  ): F[BlockAcceptanceResult[B]]

  def acceptBlock(
    block: Signed[B],
    context: BlockAcceptanceContext[F]
  ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}
