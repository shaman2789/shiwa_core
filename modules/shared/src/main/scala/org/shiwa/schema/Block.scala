package org.shiwa.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.shiwa.ext.cats.syntax.next._
import org.shiwa.schema.height.Height
import org.shiwa.schema.transaction.Transaction
import org.shiwa.security.Hashed
import org.shiwa.security.signature.Signed

case class ParentBlockReference(parents: NonEmptyList[BlockReference])
case class BlockData[T <: Transaction](transactions: NonEmptySet[Signed[T]])

trait Block[T <: Transaction] extends Fiber[ParentBlockReference, BlockData[T]] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[T]]

  val height: Height = parent.maximum.height.next

  def reference: ParentBlockReference = ParentBlockReference(parent)

  def data: BlockData[T] = BlockData(transactions)
}

object Block {

  implicit class HashedOps(hashedBlock: Hashed[Block[_]]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }

  trait BlockConstructor[T <: Transaction, B <: Block[T]] {
    def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[T]]): B
  }
}
