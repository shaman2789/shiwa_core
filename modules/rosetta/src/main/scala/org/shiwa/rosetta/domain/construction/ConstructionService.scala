package org.shiwa.rosetta.domain.construction

import cats.data.{EitherT, NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.eq.catsSyntaxEq
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.shiwa.ext.crypto._
import org.shiwa.json.JsonBinarySerializer
import org.shiwa.kryo.KryoSerializer
import org.shiwa.rosetta.domain._
import org.shiwa.rosetta.domain.amount.Amount
import org.shiwa.rosetta.domain.api.construction.ConstructionMetadata.MetadataResult
import org.shiwa.rosetta.domain.api.construction.ConstructionParse
import org.shiwa.rosetta.domain.api.construction.ConstructionPayloads.PayloadsResult
import org.shiwa.rosetta.domain.error._
import org.shiwa.rosetta.domain.operation._
import org.shiwa.schema.address.Address
import org.shiwa.schema.transaction._
import org.shiwa.security.SecurityProvider
import org.shiwa.security.hex.Hex
import org.shiwa.security.key.ops._
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._

trait ConstructionService[F[_]] {
  def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier]
  def getAccountIdentifiers(operations: List[Operation]): Option[NonEmptyList[AccountIdentifier]]
  def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier]
  def parseTransaction(hex: Hex, isSigned: Boolean): EitherT[F, ConstructionError, ConstructionParse.ParseResult]
  def combineTransaction(hex: Hex, signature: RosettaSignature): EitherT[F, ConstructionError, Hex]
  def getMetadata(publicKeys: NonEmptyList[RosettaPublicKey]): EitherT[F, ConstructionError, MetadataResult]
  def getPayloads(operations: NonEmptyList[Operation], metadata: MetadataResult): EitherT[F, ConstructionError, PayloadsResult]
}

object ConstructionService {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    getLastAcceptedReference: Address => F[TransactionReference],
    salt: F[TransactionSalt]
  ): ConstructionService[F] = new ConstructionService[F] {
    def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier] =
      publicKey.hexBytes
        .toPublicKeyByEC[F]
        .map(_.toAddress)
        .map(AccountIdentifier(_, None))
        .attemptT
        .leftMap(_ => InvalidPublicKey)

    def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier] =
      JsonBinarySerializer
        .deserialize[Signed[SHITransaction]](hex.toBytes)
        .liftTo[F]
        .flatMap(_.toHashed[F])
        .map(_.hash)
        .map(TransactionIdentifier(_))
        .attemptT
        .leftMap(_ => MalformedTransaction)

    def getAccountIdentifiers(operations: List[Operation]): Option[NonEmptyList[AccountIdentifier]] = {
      val accountIdentifiers =
        operations.filter(_.amount.value.isNegative).map(_.account)

      NonEmptyList.fromList(accountIdentifiers)
    }

    def parseTransaction(hex: Hex, isSigned: Boolean): EitherT[F, ConstructionError, ConstructionParse.ParseResult] =
      if (isSigned) {
        parseSignedTransaction(hex)
      } else {
        parseUnsignedTransaction(hex)
      }

    def combineTransaction(hex: Hex, signature: RosettaSignature): EitherT[F, ConstructionError, Hex] =
      EitherT
        .fromEither(JsonBinarySerializer.deserialize[SHITransaction](hex.toBytes))
        .leftMap(_ => MalformedTransaction)
        .flatMap { transaction =>
          EitherT {
            signature.publicKey.hexBytes.toPublicKeyByEC
              .map(pk => SignatureProof(pk.toId, Signature(signature.hexBytes)).asRight[ConstructionError])
          }.map { proof =>
            JsonBinarySerializer.serialize(Signed[SHITransaction](transaction, NonEmptySet.of(proof)))
          }
            .map(Hex.fromBytes(_))
        }

    def getMetadata(publicKeys: NonEmptyList[RosettaPublicKey]): EitherT[F, ConstructionError, MetadataResult] =
      publicKeys match {
        case NonEmptyList(key, Nil) =>
          EitherT.liftF(
            key.hexBytes.toPublicKeyByEC
              .flatMap(publicKeyByEC => getLastAcceptedReference(publicKeyByEC.toAddress))
              .map(ref => MetadataResult(ref, none))
          )
        case _ => EitherT.leftT[F, MetadataResult](ExactlyOnePublicKeyRequired)
      }

    def getPayloads(
      operations: NonEmptyList[Operation],
      metadataResult: MetadataResult
    ): EitherT[F, ConstructionError, PayloadsResult] =
      EitherT(
        salt.map(transactionSalt =>
          for {
            (positiveOperation, negativeOperation) <- getPayloadOperations(operations)

            transactionAmount <- Either.fromOption(
              positiveOperation.amount.value.toTransactionAmount,
              InvalidOperationAmount(positiveOperation.amount.value.value)
            )

            sourceAddress = negativeOperation.account.address
            transactionFee <-
              metadataResult.suggestedFee match {
                case None      => TransactionFee.zero.asRight
                case Some(amt) => Either.fromOption(amt.value.toTransactionFee, InvalidSuggestedFee)
              }

            unsignedTx =
              SHITransaction(
                source = sourceAddress,
                destination = positiveOperation.account.address,
                amount = transactionAmount,
                fee = transactionFee,
                parent = metadataResult.lastReference,
                salt = transactionSalt
              )

            serializedTxn = JsonBinarySerializer.serialize(unsignedTx)

            unsignedTxHash <- unsignedTx.hash.leftMap[ConstructionError](e => SerializationError(e.getMessage))
            signedBytes = Hex.fromBytes(unsignedTxHash.getBytes)

            payload = SigningPayload(AccountIdentifier(sourceAddress, none), signedBytes, SignatureType.ECDSA)

          } yield PayloadsResult(Hex.fromBytes(serializedTxn), NonEmptyList.one(payload))
        )
      )

    private def transactionToOperations(transaction: Transaction): NonEmptyList[Operation] = {
      val positiveAmount = Amount.fromTransactionAmount(transaction.amount)
      val positiveTransfer = (transaction.destination, positiveAmount, OperationIndex(1L))
      val negativeTransfer = (transaction.source, positiveAmount.negate, OperationIndex(0L))

      NonEmptyList.of(negativeTransfer, positiveTransfer).map {
        case (address, amount, operationIndex) =>
          Operation(
            OperationIdentifier(operationIndex),
            none,
            OperationType.Transfer,
            none,
            AccountIdentifier(address, none),
            amount
          )
      }
    }

    private def parseSignedTransaction(hex: Hex): EitherT[F, ConstructionError, ConstructionParse.ParseResult] = {
      val result = for {
        signedTransaction <- JsonBinarySerializer.deserialize[Signed[SHITransaction]](hex.toBytes).toEitherT
        operations = transactionToOperations(signedTransaction)
        proofs <- signedTransaction.proofs.toNonEmptyList.traverse(_.id.hex.toPublicKey).attemptT
        accountIds = proofs.map(_.toAddress).map(AccountIdentifier(_, none))
      } yield ConstructionParse.ParseResult(operations, accountIds.some)

      result.leftMap(_ => MalformedTransaction)
    }

    private def parseUnsignedTransaction(hex: Hex): EitherT[F, ConstructionError, ConstructionParse.ParseResult] =
      EitherT.fromEither(
        JsonBinarySerializer
          .deserialize[SHITransaction](hex.toBytes)
          .map(t => ConstructionParse.ParseResult(transactionToOperations(t), none))
          .leftMap(_ => MalformedTransaction)
      )

    private def getPayloadOperations(operations: NonEmptyList[Operation]): Either[ConstructionError, (Operation, Operation)] =
      for {
        (first, second) <- operations.toList match {
          case first :: second :: Nil => (first, second).asRight
          case _                      => InvalidNumberOfOperations(2).asLeft
        }

        _ <- Either.cond(
          first.amount.value.negate === second.amount.value,
          (),
          NegationPairMismatch
        )

      } yield if (first.amount.value.isPositive) first -> second else second -> first
  }
}