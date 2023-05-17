package org.shiwa.dag.l1.domain.consensus.block.storage

import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.dag.l1.domain.consensus.block.RoundData
import org.shiwa.dag.l1.domain.consensus.round.RoundId
import org.shiwa.schema.Block
import org.shiwa.schema.transaction.Transaction

import io.chrisdavenport.mapref.MapRef

class ConsensusStorage[F[_], T <: Transaction, B <: Block[T]](
  val ownConsensus: Ref[F, Option[RoundData[T, B]]],
  val peerConsensuses: MapRef[F, RoundId, Option[RoundData[T, B]]]
)

object ConsensusStorage {

  def make[F[_]: Sync, T <: Transaction, B <: Block[T]]: F[ConsensusStorage[F, T, B]] =
    for {
      peerConsensuses <- MapRef.ofConcurrentHashMap[F, RoundId, RoundData[T, B]]()
      ownConsensus <- Ref.of[F, Option[RoundData[T, B]]](None)
    } yield new ConsensusStorage(ownConsensus, peerConsensuses)
}