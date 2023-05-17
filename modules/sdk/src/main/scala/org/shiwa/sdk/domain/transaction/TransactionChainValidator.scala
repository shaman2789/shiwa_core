package org.shiwa.sdk.domain.transaction

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.transaction.{Transaction, TransactionReference}
import org.shiwa.sdk.domain.transaction.TransactionChainValidator.{TransactionChainValidationErrorOr, TransactionNel}
import org.shiwa.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

trait TransactionChainValidator[F[_], T <: Transaction] {

  def validate(
    transactions: NonEmptySet[Signed[T]]
  ): F[TransactionChainValidationErrorOr[Map[Address, TransactionNel[T]]]]
}

object TransactionChainValidator {

  def make[F[_]: Async: KryoSerializer, T <: Transaction]: TransactionChainValidator[F, T] =
    new TransactionChainValidator[F, T] {

      def validate(
        transactions: NonEmptySet[Signed[T]]
      ): F[TransactionChainValidationErrorOr[Map[Address, TransactionNel[T]]]] =
        transactions.toNonEmptyList
          .groupBy(_.value.source)
          .toList
          .traverse {
            case (address, txs) =>
              validateChainForSingleAddress(address, txs)
                .map(chainedTxs => address -> chainedTxs)
                .value
                .map(_.toValidatedNec)
          }
          .map(_.foldMap(_.map(Chain(_))))
          .map(_.map(_.toList.toMap))

      private def validateChainForSingleAddress(
        address: Address,
        txs: TransactionNel[T]
      ): EitherT[F, TransactionChainBroken, TransactionNel[T]] = {
        val sortedTxs = txs.sortBy(_.ordinal)
        val initChain = NonEmptyList.of(sortedTxs.head).asRight[TransactionChainBroken].toEitherT[F]
        sortedTxs.tail
          .foldLeft(initChain) { (errorOrParents, tx) =>
            errorOrParents.flatMap { parents =>
              EitherT(TransactionReference.of(parents.head).map { parentRef =>
                Either.cond(
                  parentRef === tx.parent,
                  tx :: parents,
                  TransactionChainBroken(address, tx.parent)
                )
              })
            }
          }
          .map(_.reverse)
      }
    }

  @derive(eqv, show)
  case class TransactionChainBroken(address: Address, referenceNotFound: TransactionReference)

  type TransactionNel[T <: Transaction] = NonEmptyList[Signed[T]]
  type TransactionChainValidationErrorOr[A] = ValidatedNec[TransactionChainBroken, A]
}