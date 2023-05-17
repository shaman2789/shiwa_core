package org.shiwa.dag.l1.domain.consensus.block

import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

import org.shiwa.dag.l1.Main
import org.shiwa.dag.l1.domain.consensus.block.BlockConsensusInput.Proposal
import org.shiwa.dag.l1.domain.consensus.round.RoundId
import org.shiwa.dag.transaction.TransactionGenerator
import org.shiwa.ext.cats.effect.ResourceIO
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.BlockReference
import org.shiwa.schema.address.Address
import org.shiwa.schema.block.{SHIBlock, Tips}
import org.shiwa.schema.height.Height
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.{SHITransaction, TransactionFee, TransactionReference}
import org.shiwa.sdk.domain.transaction.TransactionValidator
import org.shiwa.sdk.sdkKryoRegistrar
import org.shiwa.security.hash.ProofsHash
import org.shiwa.security.hex.Hex
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.SignedValidator
import org.shiwa.security.signature.signature.SignatureProof
import org.shiwa.security.{KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object RoundDataSuite extends ResourceSuite with Checkers with TransactionGenerator {

  override type Res =
    (KryoSerializer[IO], SecurityProvider[IO], KeyPair, KeyPair, Address, Address, TransactionValidator[IO, SHITransaction])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
      SecurityProvider.forAsync[IO].flatMap { implicit sp =>
        for {
          srcKey <- KeyPairGenerator.makeKeyPair[IO].asResource
          dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
          srcAddress = srcKey.getPublic.toAddress
          dstAddress = dstKey.getPublic.toAddress
          signedValidator = SignedValidator.make
          txValidator = TransactionValidator.make[F, SHITransaction](signedValidator)
        } yield (kp, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator)
      }
    }

  implicit val logger = Slf4jLogger.getLogger[IO]

  val roundId = RoundId(UUID.randomUUID())
  val peerIdA = PeerId(Hex("peerA"))
  val peerIdB = PeerId(Hex("peerB"))
  val peerIdC = PeerId(Hex("peerC"))
  val tips = Tips(NonEmptyList.of(BlockReference(Height(1L), ProofsHash("0000"))))
  val baseProposal = Proposal[SHITransaction](roundId, peerIdA, peerIdA, Set.empty, Set.empty, tips)

  val baseRoundData =
    RoundData[SHITransaction, SHIBlock](
      roundId,
      FiniteDuration(1000L, TimeUnit.MINUTES),
      Set.empty,
      peerIdA,
      baseProposal,
      None,
      None,
      Map.empty,
      Map.empty,
      Map.empty,
      tips
    )

  test("formBlock should return None when there were no transactions in RoundData") {
    case (kp, _, _, _, _, _, txValidator) =>
      implicit val kryoPool = kp

      baseRoundData.formBlock(txValidator).map(maybeBlock => expect.same(None, maybeBlock))
  }

  test(
    "formBlock should return the block with properly selected transactions - preferring the ones with higher fee if there are concurrent chains of transactions"
  ) {
    case (kp, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3)
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 3, TransactionFee(1L))
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy[SHITransaction](senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          SHIBlock(baseProposal.tips.value, txsA2.map(_.signed).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly from the pool of transactions from all facilitators") {
    case (kp, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 1)
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 1, TransactionFee(1L), txRef)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy[SHITransaction](senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          SHIBlock(baseProposal.tips.value, (txsA.map(_.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly when concurrent transactions are proposed by different facilitators") {
    case (kp, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 2)
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 2, TransactionFee(1L), txRef)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy[SHITransaction](senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          SHIBlock(baseProposal.tips.value, (NonEmptyList.one(txsA.head.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should discard transactions that are invalid") {
    case (kp, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3)
        txToBreak = txsA.toList(1).signed
        brokenTx = txToBreak.copy(proofs = txToBreak.proofs.map(sp => SignatureProof(dstKey.getPublic.toId, sp.signature)))
        txs = Set(txsA.head.signed, brokenTx, txsA.last.signed)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txs),
          peerProposals = Map(
            peerIdB -> baseProposal.copy[SHITransaction](senderId = peerIdB, transactions = Set.empty),
            peerIdC -> baseProposal.copy[SHITransaction](senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          SHIBlock(baseProposal.tips.value, NonEmptyList.one(txsA.head.signed).toNes).some,
          result
        )
  }
}