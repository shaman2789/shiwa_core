package org.shiwa.schema

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.semver.SnapshotVersion
import org.shiwa.schema.transaction.{Transaction, TransactionReference}
import org.shiwa.security.hash.Hash
import org.shiwa.syntax.sortedCollection._

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object snapshot {

  trait StateProof {
    val lastTxRefsProof: Hash
    val balancesProof: Hash
  }

  trait FullSnapshot[T <: Transaction, B <: Block[T], P <: StateProof, SI <: SnapshotInfo[P]] extends Snapshot[T, B] {
    val info: SI
  }

  trait IncrementalSnapshot[T <: Transaction, B <: Block[T], P <: StateProof] extends Snapshot[T, B] {
    val stateProof: P
    val version: SnapshotVersion
  }

  trait Snapshot[T <: Transaction, B <: Block[T]] {
    val ordinal: SnapshotOrdinal
    val height: Height
    val subHeight: SubHeight
    val lastSnapshotHash: Hash
    val blocks: SortedSet[BlockAsActiveTip[B]]
    val tips: SnapshotTips

    def activeTips[F[_]: Async: KryoSerializer]: F[SortedSet[ActiveTip]] =
      blocks.toList.traverse { blockAsActiveTip =>
        BlockReference
          .of(blockAsActiveTip.block)
          .map(blockRef => ActiveTip(blockRef, blockAsActiveTip.usageCount, ordinal))
      }.map(_.toSortedSet.union(tips.remainedActive))
  }

  trait SnapshotInfo[P <: StateProof] {
    val lastTxRefs: SortedMap[Address, TransactionReference]
    val balances: SortedMap[Address, Balance]

    def stateProof[F[_]: MonadThrow: KryoSerializer]: F[P]
  }

  @derive(encoder, decoder, show)
  case class SnapshotMetadata(ordinal: SnapshotOrdinal, hash: Hash, lastSnapshotHash: Hash)

}
