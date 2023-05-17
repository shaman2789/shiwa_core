package org.shiwa.schema.block

import cats.data.{NonEmptyList, NonEmptySet}

import org.shiwa.ext.cats.data.OrderBasedOrdering
import org.shiwa.ext.codecs.NonEmptySetCodec
import org.shiwa.schema.Block.BlockConstructor
import org.shiwa.schema._
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.Decoder

@derive(show, eqv, encoder, decoder, order)
case class SHIBlock(
  parent: NonEmptyList[BlockReference],
  transactions: NonEmptySet[Signed[SHITransaction]]
) extends Block[SHITransaction] {}

object SHIBlock {
  implicit object OrderingInstance extends OrderBasedOrdering[SHIBlock]

  implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[SHITransaction]]] =
    NonEmptySetCodec.decoder[Signed[SHITransaction]]

  implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[SHIBlock]]

  implicit val constructor = new BlockConstructor[SHITransaction, SHIBlock] {
    def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[SHITransaction]]): SHIBlock =
      SHIBlock(parents, transactions)
  }
}
