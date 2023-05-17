package org.shiwa.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency
import org.shiwa.currency.schema.currency.{CurrencyBlock, CurrencySnapshotInfo, CurrencyTransaction}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.transaction.RewardTransaction
import org.shiwa.sdk.domain.block.processing._
import org.shiwa.security.signature.Signed
import org.shiwa.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait CurrencySnapshotAcceptanceManager[F[_]] {
  def accept(
    blocksForAcceptance: List[Signed[CurrencyBlock]],
    lastSnapshotContext: CurrencySnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[CurrencyTransaction]] => F[SortedSet[RewardTransaction]]
  ): F[
    (
      BlockAcceptanceResult[CurrencyBlock],
      SortedSet[RewardTransaction],
      CurrencySnapshotInfo,
      currency.CurrencySnapshotStateProof
    )
  ]
}

object CurrencySnapshotAcceptanceManager {
  def make[F[_]: Async: KryoSerializer](
    blockAcceptanceManager: BlockAcceptanceManager[F, CurrencyTransaction, CurrencyBlock],
    collateral: Amount
  ) = new CurrencySnapshotAcceptanceManager[F] {

    def accept(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[CurrencyTransaction]] => F[SortedSet[RewardTransaction]]
    ) = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

      rewards <- calculateRewardsFn(acceptedTransactions)

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances,
        rewards
      )

      csi = CurrencySnapshotInfo(transactionsRefs, updatedBalancesByRewards)
      stateProof <- csi.stateProof

    } yield (acceptanceResult, acceptedRewardTxs, csi, stateProof)

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[CurrencyBlock]],
      lastSnapshotContext: CurrencySnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

  }

}
