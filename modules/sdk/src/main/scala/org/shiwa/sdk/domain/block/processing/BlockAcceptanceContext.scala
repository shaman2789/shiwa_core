package org.shiwa.sdk.domain.block.processing

import cats.Applicative
import cats.syntax.applicative._

import org.shiwa.schema.BlockReference
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.transaction.TransactionReference

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAcceptanceContext[F[_]] {

  def getBalance(address: Address): F[Option[Balance]]

  def getLastTxRef(address: Address): F[Option[TransactionReference]]

  def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]]

  def getCollateral: Amount

}

object BlockAcceptanceContext {

  def fromStaticData[F[_]: Applicative](
    balances: Map[Address, Balance],
    lastTxRefs: Map[Address, TransactionReference],
    parentUsages: Map[BlockReference, NonNegLong],
    collateral: Amount
  ): BlockAcceptanceContext[F] =
    new BlockAcceptanceContext[F] {

      def getBalance(address: Address): F[Option[Balance]] =
        balances.get(address).pure[F]

      def getLastTxRef(address: Address): F[Option[TransactionReference]] =
        lastTxRefs.get(address).pure[F]

      def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]] =
        parentUsages.get(blockReference).pure[F]

      def getCollateral = collateral
    }

}
