package org.shiwa.dag.l1.domain.block

import cats.effect.kernel.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block.HashedOps
import org.shiwa.schema.transaction.{Transaction, TransactionReference}
import org.shiwa.schema.{Block, BlockReference}
import org.shiwa.security.Hashed
import org.shiwa.security.signature.Signed

object BlockRelations {

  def dependsOn[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    blocks: Hashed[B]
  )(block: Signed[B]): F[Boolean] = dependsOn[F, T, B](Set(blocks))(block)

  def dependsOn[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    blocks: Set[Hashed[B]],
    references: Set[BlockReference] = Set.empty
  )(block: Signed[B]): F[Boolean] = {
    def dstAddresses = blocks.flatMap(_.transactions.toSortedSet.toList.map(_.value.destination))

    def isChild =
      block.parent.exists(parentRef => (blocks.map(_.ownReference) ++ references).exists(_ === parentRef))
    def hasReferencedAddress = block.transactions.map(_.source).exists(srcAddress => dstAddresses.exists(_ === srcAddress))
    def hasReferencedTx = blocks.toList
      .flatTraverse(_.transactions.toSortedSet.toList.traverse(TransactionReference.of(_)))
      .map(_.toSet)
      .map { txRefs =>
        block.transactions.map(_.parent).exists(txnParentRef => txRefs.exists(_ === txnParentRef))
      }

    if (isChild || hasReferencedAddress) true.pure[F] else hasReferencedTx
  }
}
