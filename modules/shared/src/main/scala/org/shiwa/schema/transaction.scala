package org.shiwa.schema

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.semigroup._

import scala.util.Try

import org.shiwa.ext.cats.data.OrderBasedOrdering
import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Amount
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed
import org.shiwa.security.{Encodable, Hashed, SecureRandom}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import enumeratum._
import eu.timepit.refined.auto.{autoInfer, autoRefineV, autoUnwrap}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.Lens
import monocle.macros.GenLens

object transaction {

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionAmount(value: PosLong)

  object TransactionAmount {
    implicit def toAmount(amount: TransactionAmount): Amount = Amount(amount.value)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionFee(value: NonNegLong)

  object TransactionFee {
    implicit def toAmount(fee: TransactionFee): Amount = Amount(fee.value)
    val zero: TransactionFee = TransactionFee(0L)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionOrdinal(value: NonNegLong) {
    def next: TransactionOrdinal = TransactionOrdinal(value |+| 1L)
  }

  object TransactionOrdinal {
    val first: TransactionOrdinal = TransactionOrdinal(1L)
  }

  @derive(decoder, encoder, order, show)
  case class TransactionReference(ordinal: TransactionOrdinal, hash: Hash)

  object TransactionReference {
    val empty: TransactionReference = TransactionReference(TransactionOrdinal(0L), Hash.empty)

    val _Hash: Lens[TransactionReference, Hash] = GenLens[TransactionReference](_.hash)
    val _Ordinal: Lens[TransactionReference, TransactionOrdinal] = GenLens[TransactionReference](_.ordinal)

    def of[F[_]: Async: KryoSerializer, T <: Transaction](signedTransaction: Signed[T]): F[TransactionReference] =
      signedTransaction.value.hashF.map(TransactionReference(signedTransaction.ordinal, _))

    def of(hashedTransaction: Hashed[Transaction]): TransactionReference =
      TransactionReference(hashedTransaction.ordinal, hashedTransaction.hash)

  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class TransactionSalt(value: Long)

  object TransactionSalt {
    def generate[F[_]: Async]: F[TransactionSalt] =
      SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(TransactionSalt.apply)
  }

  @derive(decoder, encoder, order, show)
  case class TransactionData(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee
  )

  trait Transaction extends Fiber[TransactionReference, TransactionData] with Encodable {
    import Transaction._

    val source: Address
    val destination: Address
    val amount: TransactionAmount
    val fee: TransactionFee
    val parent: TransactionReference
    val salt: TransactionSalt

    def reference = parent
    def data = TransactionData(source, destination, amount, fee)

    // WARN: Transactions hash needs to be calculated with Kryo instance having setReferences=true, to be backward compatible
    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            amount.coerce.value.toHexString,
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    val ordinal: TransactionOrdinal = parent.ordinal.next
  }

  object Transaction {

    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")
  }

  @derive(decoder, encoder, order, show)
  case class SHITransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction

  object SHITransaction {

    implicit object OrderingInstance extends OrderBasedOrdering[SHITransaction]

    val _Source: Lens[SHITransaction, Address] = GenLens[SHITransaction](_.source)
    val _Destination: Lens[SHITransaction, Address] = GenLens[SHITransaction](_.destination)

    val _Amount: Lens[SHITransaction, TransactionAmount] = GenLens[SHITransaction](_.amount)
    val _Fee: Lens[SHITransaction, TransactionFee] = GenLens[SHITransaction](_.fee)
    val _Parent: Lens[SHITransaction, TransactionReference] = GenLens[SHITransaction](_.parent)

    val _ParentHash: Lens[SHITransaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[SHITransaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)
  }

  @derive(decoder, encoder, order, show)
  case class RewardTransaction(
    destination: Address,
    amount: TransactionAmount
  )

  object RewardTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[RewardTransaction]
  }

  @derive(encoder)
  case class TransactionView[T <: Transaction](
    transaction: T,
    hash: Hash,
    status: TransactionStatus
  )

  @derive(eqv, show)
  sealed trait TransactionStatus extends EnumEntry

  object TransactionStatus extends Enum[TransactionStatus] with TransactionStatusCodecs {
    val values = findValues

    case object Waiting extends TransactionStatus
  }

  trait TransactionStatusCodecs {
    implicit val encode: Encoder[TransactionStatus] = Encoder.encodeString.contramap[TransactionStatus](_.entryName)
    implicit val decode: Decoder[TransactionStatus] =
      Decoder.decodeString.emapTry(s => Try(TransactionStatus.withName(s)))
  }

}