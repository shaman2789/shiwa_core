package org.shiwa.sdk.infrastructure.block.processing

import cats.data.{EitherT, NonEmptyList}
import cats.effect.kernel.Ref
import cats.effect.{IO, Resource}
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.validated._

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.BlockReference
import org.shiwa.schema.address.Address
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.height.Height
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.sdk.domain.block.generators.signedSHIBlockGen
import org.shiwa.sdk.domain.block.processing.{UsageCount, initUsageCount, _}
import org.shiwa.sdk.domain.transaction.TransactionChainValidator
import org.shiwa.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.signature.Signed
import org.shiwa.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockAcceptanceManagerSuite extends MutableIOSuite with Checkers {

  val validAcceptedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "1"))
  val validRejectedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "2"))
  val validAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "3"))
  val validInitiallyAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "4"))
  val invalidParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "5"))
  val commonAddress = Address("SHI0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, BlockAcceptanceManagerSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  def mkBlockAcceptanceManager(acceptInitiallyAwaiting: Boolean = true)(implicit kryo: KryoSerializer[IO]) =
    Ref[F].of[Map[Signed[SHIBlock], Boolean]](Map.empty.withDefaultValue(false)).map { state =>
      val blockLogic = new BlockAcceptanceLogic[IO, SHITransaction, SHIBlock] {
        override def acceptBlock(
          block: Signed[SHIBlock],
          txChains: Map[Address, TransactionChainValidator.TransactionNel[SHITransaction]],
          context: BlockAcceptanceContext[IO],
          contextUpdate: BlockAcceptanceContextUpdate
        ): EitherT[IO, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)] =
          EitherT(
            for {
              wasSeen <- state.getAndUpdate(wasSeen => wasSeen + ((block, true)))
            } yield
              block.parent.head match {
                case `validAcceptedParent` =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validRejectedParent` =>
                  ParentNotFound(validRejectedParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validAwaitingParent` =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if !wasSeen.apply(block) =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && acceptInitiallyAwaiting =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && !acceptInitiallyAwaiting =>
                  ParentNotFound(validInitiallyAwaitingParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case _ => ???
              }
          )
      }

      val blockValidator = new BlockValidator[IO, SHITransaction, SHIBlock] {

        override def validate(
          signedBlock: Signed[SHIBlock],
          params: BlockValidationParams
        ): IO[BlockValidationErrorOr[
          (Signed[SHIBlock], Map[Address, TransactionChainValidator.TransactionNel[SHITransaction]])
        ]] = signedBlock.parent.head match {
          case `invalidParent` => IO.pure(NotEnoughParents(0, 0).invalidNec)
          case _ => IO.pure((signedBlock, Map.empty[Address, TransactionChainValidator.TransactionNel[SHITransaction]]).validNec)

        }
      }
      BlockAcceptanceManager.make[IO, SHITransaction, SHIBlock](blockLogic, blockValidator)
    }

  test("accept valid block") { implicit ks =>
    forall(validAcceptedSHIBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult[SHIBlock](
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validAcceptedParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager()
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject valid block") { implicit ks =>
    forall(validRejectedSHIBlocksGen) {
      case (acceptedBlocks, rejectedBlocks) =>
        val expected = BlockAcceptanceResult[SHIBlock](
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          rejectedBlocks.sorted.reverse.map(b => (b, ParentNotFound(validRejectedParent)))
        )

        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ rejectedBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("awaiting valid block") { implicit ks =>
    forall(validAwaitingSHIBlocksGen) {
      case (acceptedBlocks, awaitingBlocks) =>
        val expected = BlockAcceptanceResult[SHIBlock](
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          awaitingBlocks.sorted.reverse
            .map(b => (b, SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))))
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ awaitingBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("accept initially awaiting valid block") { implicit ks =>
    forall(validInitiallyAwaitingSHIBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult[SHIBlock](
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validInitiallyAwaitingParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )

      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = true)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject initially awaiting valid block") { implicit ks =>
    forall(validInitiallyAwaitingSHIBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult[SHIBlock](
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ParentNotFound(validInitiallyAwaitingParent)))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("invalid block") { implicit ks =>
    forall(invalidSHIBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ValidationFailed(NonEmptyList.of(NotEnoughParents(0, 0)))))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  def validAcceptedSHIBlocksGen = dagBlocksGen(1, validAcceptedParent)

  def validRejectedSHIBlocksGen =
    for {
      rejected <- dagBlocksGen(1, validRejectedParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, rejected)

  def validAwaitingSHIBlocksGen =
    for {
      awaiting <- dagBlocksGen(1, validAwaitingParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, awaiting)

  def validInitiallyAwaitingSHIBlocksGen = dagBlocksGen(1, validInitiallyAwaitingParent)

  def invalidSHIBlocksGen = dagBlocksGen(1, invalidParent)

  def dagBlocksGen(minimalSize: Int, parent: BlockReference) =
    for {
      size <- Gen.choose(minimalSize, 3)
      blocks <- Gen.listOfN(size, signedSHIBlockGen)
    } yield blocks.map(substituteParent(parent))

  def substituteParent(parent: BlockReference)(signedBlock: Signed[SHIBlock]) =
    signedBlock.copy(value = signedBlock.value.copy(parent = NonEmptyList.of(parent)))
}
