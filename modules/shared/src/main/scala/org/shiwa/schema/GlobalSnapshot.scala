package org.shiwa.schema

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.ext.cats.syntax.next.catsSyntaxNext
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.semver.SnapshotVersion
import org.shiwa.schema.snapshot.{FullSnapshot, IncrementalSnapshot}
import org.shiwa.schema.transaction.{RewardTransaction, SHITransaction}
import org.shiwa.security.Hashed
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.hex.Hex
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelSnapshotBinary
import org.shiwa.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

@derive(eqv, show, encoder, decoder)
case class GlobalIncrementalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip[SHIBlock]],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  tips: SnapshotTips,
  stateProof: GlobalSnapshotStateProof,
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) extends IncrementalSnapshot[SHITransaction, SHIBlock, GlobalSnapshotStateProof]

object GlobalIncrementalSnapshot {
  def fromGlobalSnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: GlobalSnapshot): F[GlobalIncrementalSnapshot] =
    snapshot.info.stateProof.map { stateProof =>
      GlobalIncrementalSnapshot(
        snapshot.ordinal,
        snapshot.height,
        snapshot.subHeight,
        snapshot.lastSnapshotHash,
        snapshot.blocks,
        snapshot.stateChannelSnapshots,
        snapshot.rewards,
        snapshot.epochProgress,
        snapshot.nextFacilitators,
        snapshot.tips,
        stateProof
      )
    }
}

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip[SHIBlock]],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfoV1,
  tips: SnapshotTips
) extends FullSnapshot[SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalSnapshotInfoV1] {}

object GlobalSnapshot {

  def mkGenesis(balances: Map[Address, Balance], startingEpochProgress: EpochProgress): GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Coinbase.hash,
      SortedSet.empty[BlockAsActiveTip[SHIBlock]],
      SortedMap.empty,
      SortedSet.empty,
      startingEpochProgress,
      nextFacilitators,
      GlobalSnapshotInfoV1(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)),
      SnapshotTips(
        SortedSet.empty[DeprecatedTip],
        mkActiveTips(8)
      )
    )

  def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[GlobalSnapshot]): F[GlobalIncrementalSnapshot] =
    genesis.info.stateProof.map { stateProof =>
      GlobalIncrementalSnapshot(
        genesis.ordinal.next,
        genesis.height,
        genesis.subHeight.next,
        genesis.hash,
        SortedSet.empty,
        SortedMap.empty,
        SortedSet.empty,
        genesis.epochProgress.next,
        nextFacilitators,
        genesis.tips,
        stateProof
      )
    }

  val nextFacilitators: NonEmptyList[PeerId] =
    NonEmptyList
      .of(
        "e0c1ee6ec43510f0e16d2969a7a7c074a5c8cdb477c074fe9c32a9aad8cbc8ff1dff60bb81923e0db437d2686a9b65b86c403e6a21fa32b6acc4e61be4d70925"
      )
      .map(s => PeerId(Hex(s)))

  private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
    List
      .range(0, n.value)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSortedSet

}
