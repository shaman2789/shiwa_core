package org.shiwa.schema

import org.shiwa.ext.cats.data.OrderBasedOrdering
import org.shiwa.ext.refined._
import org.shiwa.schema.balance.Balance
import org.shiwa.security.Base58

import derevo.cats.{order, show}
import derevo.circe.magnolia._
import derevo.derive
import eu.timepit.refined.api.{Refined, Validate}
import eu.timepit.refined.cats._
import eu.timepit.refined.refineV
import io.circe._
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import io.getquill.MappedEncoding

object address {

  @derive(decoder, encoder, keyDecoder, keyEncoder, order, show)
  @newtype
  case class Address(value: SHIAddress)

  object Address {
    implicit object OrderingInstance extends OrderBasedOrdering[Address]

    implicit val quillEncode: MappedEncoding[Address, String] = MappedEncoding[Address, String](_.coerce.value)
    implicit val quillDecode: MappedEncoding[String, Address] =
      MappedEncoding[String, Address](
        refineV[SHIAddressRefined].apply[String](_) match {
          // TODO: don't like it though an example with java.util.UUID.fromString in Quills docs suggests you can throw
          //  like this, should be possible to do it better
          case Left(e)  => throw new Throwable(e)
          case Right(a) => Address(a)
        }
      )

    implicit val decodeSHIAddress: Decoder[SHIAddress] =
      decoderOf[String, SHIAddressRefined]

    implicit val encodeSHIAddress: Encoder[SHIAddress] =
      encoderOf[String, SHIAddressRefined]

    implicit val keyDecodeSHIAddress: KeyDecoder[SHIAddress] = new KeyDecoder[SHIAddress] {
      def apply(key: String): Option[SHIAddress] = refineV[SHIAddressRefined](key).toOption
    }

    implicit val keyEncodeSHIAddress: KeyEncoder[SHIAddress] = new KeyEncoder[SHIAddress] {
      def apply(key: SHIAddress): String = key.value
    }
  }

  case class AddressCache(balance: Balance)

  final case class SHIAddressRefined()

  object SHIAddressRefined {
    implicit def addressCorrectValidate: Validate.Plain[String, SHIAddressRefined] =
      Validate.fromPredicate(
        {
          case a if a == StardustCollective.address => true
          case a if a.length != 40                  => false
          case a =>
            val par = a.substring(4).filter(Character.isDigit).map(_.toString.toInt).sum % 9

            val isBase58 = Base58.isBase58(a.substring(4))
            val hasSHIPrefixAndParity = a.startsWith(s"SHI$par")

            isBase58 && hasSHIPrefixAndParity
        },
        a => s"Invalid SHI address: $a",
        SHIAddressRefined()
      )
  }

  type SHIAddress = String Refined SHIAddressRefined
}
