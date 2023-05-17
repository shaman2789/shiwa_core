package org.shiwa.sdk.infrastructure.gossip

import cats.Order._
import cats.data.NonEmptySet._
import cats.data.{NonEmptySet, Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import org.shiwa.ext.cats.syntax.validated._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.ID.Id
import org.shiwa.schema.gossip._
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.infrastructure.gossip.RumorValidator.RumorValidationErrorOr
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.SignedValidator.SignedValidationError
import org.shiwa.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait RumorValidator[F[_]] {

  def validate(signedRumor: Signed[RumorRaw]): F[RumorValidationErrorOr[Signed[RumorRaw]]]

}

object RumorValidator {

  def make[F[_]: Async: KryoSerializer](
    seedlist: Option[Set[PeerId]],
    signedValidator: SignedValidator[F]
  ): RumorValidator[F] = new RumorValidator[F] {

    def validate(
      signedRumor: Signed[RumorRaw]
    ): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
      validateSignature(signedRumor).map { signatureV =>
        signatureV
          .productR(validateOrigin(signedRumor))
          .productR(validateSeedlist(signedRumor))
      }

    def validateOrigin(signedRumor: Signed[RumorRaw]): RumorValidationErrorOr[Signed[RumorRaw]] =
      signedRumor.value match {
        case _: CommonRumorRaw => signedRumor.validNec[RumorValidationError]
        case rumor: PeerRumorRaw =>
          val signers = signedRumor.proofs.map(_.id)
          Validated.condNec(
            signers.contains(rumor.origin.toId),
            signedRumor,
            NotSignedByOrigin(rumor.origin, signers)
          )
      }

    def validateSignature(signedRumor: Signed[RumorRaw]): F[RumorValidationErrorOr[Signed[RumorRaw]]] =
      signedValidator.validateSignatures(signedRumor).map(_.errorMap(InvalidSigned))

    def validateSeedlist(signedRumor: Signed[RumorRaw]): RumorValidationErrorOr[Signed[RumorRaw]] =
      seedlist.flatMap { peers =>
        signedRumor.proofs
          .map(_.id.toPeerId)
          .toSortedSet
          .diff(peers)
          .map(_.toId)
          .toNes
      }.map(SignersNotInSeedlist).toInvalidNec(signedRumor)

  }

  @derive(eqv, show)
  sealed trait RumorValidationError
  case class InvalidHash(calculatedHash: Hash, receivedHash: Hash) extends RumorValidationError
  case class InvalidSigned(error: SignedValidationError) extends RumorValidationError
  case class SignersNotInSeedlist(signers: NonEmptySet[Id]) extends RumorValidationError
  case class NotSignedByOrigin(origin: PeerId, signers: NonEmptySet[Id]) extends RumorValidationError

  type RumorValidationErrorOr[A] = ValidatedNec[RumorValidationError, A]

}
