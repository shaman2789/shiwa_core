package org.shiwa.currency.l0.snapshot.services

import cats.effect.Async
import cats.syntax.applicative._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.transaction.RewardTransaction
import org.shiwa.sdk.domain.rewards.Rewards
import org.shiwa.sdk.infrastructure.consensus.trigger
import org.shiwa.security.signature.Signed

object Rewards {
  def make[F[_]: Async]: Rewards[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot] =
    (
      _: Signed[CurrencyIncrementalSnapshot],
      _: SortedMap[Address, Balance],
      _: SortedSet[Signed[CurrencyTransaction]],
      _: trigger.ConsensusTrigger
    ) => SortedSet.empty[RewardTransaction].pure[F]
}
