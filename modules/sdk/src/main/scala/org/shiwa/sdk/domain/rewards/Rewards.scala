package org.shiwa.sdk.domain.rewards

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.schema.Block
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.snapshot.{IncrementalSnapshot, StateProof}
import org.shiwa.schema.transaction.{RewardTransaction, Transaction}
import org.shiwa.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.shiwa.security.signature.Signed

trait Rewards[F[_], T <: Transaction, B <: Block[T], P <: StateProof, S <: IncrementalSnapshot[T, B, P]] {
  def distribute(
    lastArtifact: Signed[S],
    lastBalances: SortedMap[Address, Balance],
    acceptedTransactions: SortedSet[Signed[T]],
    trigger: ConsensusTrigger
  ): F[SortedSet[RewardTransaction]]
}
