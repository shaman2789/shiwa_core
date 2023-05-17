package org.shiwa.dag.l1.domain.transaction

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.transaction.ContextualTransactionValidator
import org.shiwa.sdk.domain.transaction.ContextualTransactionValidator.ContextualTransactionValidationError
import org.shiwa.security.Hashed
import org.shiwa.security.hash.Hash

trait TransactionService[F[_], T <: Transaction] {
  def offer(transaction: Hashed[T]): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]]
}

object TransactionService {

  def make[F[_]: Async, T <: Transaction](
    transactionStorage: TransactionStorage[F, T],
    contextualTransactionValidator: ContextualTransactionValidator[F, T]
  ): TransactionService[F, T] = new TransactionService[F, T] {

    def offer(
      transaction: Hashed[T]
    ): F[Either[NonEmptyList[ContextualTransactionValidationError], Hash]] =
      contextualTransactionValidator.validate(transaction.signed).flatMap {
        case Valid(_) =>
          transactionStorage
            .put(transaction)
            .as(transaction.hash.asRight[NonEmptyList[ContextualTransactionValidationError]])
        case Invalid(e) => e.toNonEmptyList.asLeft[Hash].pure[F]
      }

  }
}