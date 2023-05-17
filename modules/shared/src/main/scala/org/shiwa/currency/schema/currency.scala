package org.shiwa.currency.schema

import cats.MonadThrow
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.ext.cats.data.OrderBasedOrdering
import org.shiwa.ext.cats.syntax.next.catsSyntaxNext
import org.shiwa.ext.codecs.NonEmptySetCodec
import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block.BlockConstructor
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.semver.SnapshotVersion
import org.shiwa.schema.snapshot._
import org.shiwa.schema.transaction._
import org.shiwa.security.Hashed
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.signature.Signed
import org.shiwa.syntax.sortedCollection._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.circe.Decoder
import io.estatico.newtype.macros.newtype

object currency {

  @newtype
  case class TokenSymbol(symbol: String Refined MatchesRegex["[A-Z]+"])

  @derive(decoder, encoder, order, show)
  case class CurrencyTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction

  object CurrencyTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyTransaction]
  }

  @derive(show, eqv, encoder, decoder, order)
  case class CurrencyBlock(
    parent: NonEmptyList[BlockReference],
    transactions: NonEmptySet[Signed[CurrencyTransaction]]
  ) extends Block[CurrencyTransaction]

  object CurrencyBlock {

    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyBlock]

    implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[CurrencyTransaction]]] =
      NonEmptySetCodec.decoder[Signed[CurrencyTransaction]]
    implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[CurrencyBlock]]

    implicit val blockConstructor = new BlockConstructor[CurrencyTransaction, CurrencyBlock] {
      def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[CurrencyTransaction]]): CurrencyBlock =
        CurrencyBlock(parents, transactions)
    }
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotStateProof(
    lastTxRefsProof: Hash,
    balancesProof: Hash
  ) extends StateProof

  object CurrencySnapshotStateProof {
    def apply(a: (Hash, Hash)): CurrencySnapshotStateProof =
      CurrencySnapshotStateProof(a._1, a._2)
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfo(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance]
  ) extends SnapshotInfo[CurrencySnapshotStateProof] {
    def stateProof[F[_]: MonadThrow: KryoSerializer]: F[CurrencySnapshotStateProof] =
      (lastTxRefs.hashF, balances.hashF).tupled.map(CurrencySnapshotStateProof.apply)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class SnapshotFee(value: NonNegLong)

  object SnapshotFee {
    implicit def toAmount(fee: SnapshotFee): Amount = Amount(fee.value)

    val MinValue: SnapshotFee = SnapshotFee(0L)
  }

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends FullSnapshot[CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencySnapshotInfo]

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    stateProof: CurrencySnapshotStateProof,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends IncrementalSnapshot[CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof]

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: CurrencySnapshot): F[CurrencyIncrementalSnapshot] =
      snapshot.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          snapshot.ordinal,
          snapshot.height,
          snapshot.subHeight,
          snapshot.lastSnapshotHash,
          snapshot.blocks,
          snapshot.rewards,
          snapshot.tips,
          stateProof
        )
      }
  }

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Hash.empty,
        SortedSet.empty,
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, mkActiveTips(8)),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances))
      )

    def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[CurrencySnapshot]): F[CurrencyIncrementalSnapshot] =
      genesis.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.height,
          genesis.subHeight.next,
          genesis.hash,
          SortedSet.empty,
          SortedSet.empty,
          genesis.tips,
          stateProof
        )
      }

    private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
      List
        .range(0, n.value)
        .map { i =>
          ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
        }
        .toSortedSet
  }
}