package org.shiwa.wallet

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.transaction._
import org.shiwa.security.SecurityProvider
import org.shiwa.security.key.ops._
import org.shiwa.security.signature.Signed

package object transaction {

  def createTransaction[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    prevTx: Option[Signed[SHITransaction]],
    fee: TransactionFee,
    amount: TransactionAmount
  ): F[Signed[SHITransaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hashF.map(TransactionReference(tx.ordinal, _)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- TransactionSalt.generate

      tx = SHITransaction(source, destination, amount, fee, parent, salt)
      signedTx <- tx.sign(keyPair)

    } yield signedTx
}
