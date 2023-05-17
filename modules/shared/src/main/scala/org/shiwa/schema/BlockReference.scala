package org.shiwa.schema

import cats.effect.Async
import cats.syntax.functor._

import org.shiwa.ext.derevo.ordering
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.height.Height
import org.shiwa.security.hash.ProofsHash
import org.shiwa.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary

@derive(arbitrary, encoder, decoder, order, ordering, show)
case class BlockReference(height: Height, hash: ProofsHash)

object BlockReference {
  def of[F[_]: Async: KryoSerializer, B <: Block[_]](block: Signed[B]): F[BlockReference] =
    block.proofsHash.map(BlockReference(block.height, _))
}
