package org.shiwa.dag.l1.domain.snapshot.programs

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.std.Random
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.shiwa.dag.l1.Main
import org.shiwa.dag.l1.domain.address.storage.AddressStorage
import org.shiwa.dag.l1.domain.block.BlockStorage
import org.shiwa.dag.l1.domain.block.BlockStorage._
import org.shiwa.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.shiwa.dag.l1.domain.transaction.TransactionStorage
import org.shiwa.dag.l1.domain.transaction.TransactionStorage.{Accepted, LastTransactionReferenceState, Majority}
import org.shiwa.dag.transaction.TransactionGenerator
import org.shiwa.ext.cats.effect.ResourceIO
import org.shiwa.ext.collection.MapRefUtils._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction._
import org.shiwa.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.shiwa.sdk.infrastructure.snapshot._
import org.shiwa.sdk.infrastructure.snapshot.storage.LastSnapshotStorage
import org.shiwa.sdk.modules.SdkValidators
import org.shiwa.sdk.sdkKryoRegistrar
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.Signed.forAsyncKryo
import org.shiwa.security.{Hashed, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import org.scalacheck.Gen.Parameters
import org.scalacheck.rng.Seed
import weaver.SimpleIOSuite

object SnapshotProcessorSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    SnapshotProcessor[IO, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    SecurityProvider[IO],
    KryoSerializer[IO],
    (KeyPair, KeyPair, KeyPair),
    KeyPair,
    KeyPair,
    Address,
    Address,
    PeerId,
    Ref[IO, Map[Address, Balance]],
    MapRef[IO, ProofsHash, Option[StoredBlock[SHIBlock]]],
    Ref[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    MapRef[IO, Address, Option[LastTransactionReferenceState]],
    TransactionStorage[IO, SHITransaction],
    GlobalSnapshotContextFunctions[IO]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        Random.scalaUtilRandom[IO].asResource.flatMap { implicit random =>
          for {
            balancesR <- Ref.of[IO, Map[Address, Balance]](Map.empty).asResource
            blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock[SHIBlock]]().asResource
            lastSnapR <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None).asResource
            lastAccTxR <- MapRef.ofConcurrentHashMap[IO, Address, LastTransactionReferenceState]().asResource
            waitingTxsR <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[SHITransaction]]]().asResource
            transactionStorage = new TransactionStorage[IO, SHITransaction](lastAccTxR, waitingTxsR)
            validators = SdkValidators.make[IO](None, Some(Set.empty))
            currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
              BlockAcceptanceManager.make[IO, CurrencyTransaction, CurrencyBlock](validators.currencyBlockValidator),
              Amount(0L)
            )
            currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotAcceptanceManager)
            globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
              BlockAcceptanceManager.make[IO, SHITransaction, SHIBlock](validators.blockValidator),
              GlobalSnapshotStateChannelEventsProcessor.make[IO](validators.stateChannelValidator, currencySnapshotContextFns),
              Amount(0L)
            )
            globalSnapshotContextFns = GlobalSnapshotContextFunctions.make[IO](globalSnapshotAcceptanceManager)
            snapshotProcessor = {
              val addressStorage = new AddressStorage[IO] {
                def getState: IO[Map[Address, Balance]] =
                  balancesR.get

                def getBalance(address: Address): IO[balance.Balance] =
                  balancesR.get.map(b => b(address))

                def updateBalances(addressBalances: Map[Address, balance.Balance]): IO[Unit] =
                  balancesR.set(addressBalances)

                def clean: IO[Unit] = balancesR.set(Map.empty)
              }

              val blockStorage = new BlockStorage[IO, SHIBlock](blocksR)
              val lastSnapshotStorage = LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](lastSnapR)

              SHISnapshotProcessor
                .make[IO](addressStorage, blockStorage, lastSnapshotStorage, transactionStorage, globalSnapshotContextFns)
            }
            keys <- (KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO], KeyPairGenerator.makeKeyPair[IO]).tupled.asResource
            srcKey = keys._1
            dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
            srcAddress = srcKey.getPublic.toAddress
            dstAddress = dstKey.getPublic.toAddress
            peerId = PeerId.fromId(srcKey.getPublic.toId)
          } yield
            (
              snapshotProcessor,
              sp,
              kp,
              keys,
              srcKey,
              dstKey,
              srcAddress,
              dstAddress,
              peerId,
              balancesR,
              blocksR,
              lastSnapR,
              lastAccTxR,
              transactionStorage,
              globalSnapshotContextFns
            )
        }
      }
    }

  val lastSnapshotHash: Hash = Hash.arbitrary.arbitrary.pureApply(Parameters.default, Seed(1234L))

  val snapshotOrdinal8: SnapshotOrdinal = SnapshotOrdinal(8L)
  val snapshotOrdinal9: SnapshotOrdinal = SnapshotOrdinal(9L)
  val snapshotOrdinal10: SnapshotOrdinal = SnapshotOrdinal(10L)
  val snapshotOrdinal11: SnapshotOrdinal = SnapshotOrdinal(11L)
  val snapshotOrdinal12: SnapshotOrdinal = SnapshotOrdinal(12L)
  val snapshotHeight6: Height = Height(6L)
  val snapshotHeight8: Height = Height(8L)
  val snapshotSubHeight0: SubHeight = SubHeight(0L)
  val snapshotSubHeight1: SubHeight = SubHeight(1L)

  def generateSnapshotBalances(addresses: Set[Address]): SortedMap[Address, Balance] =
    SortedMap.from(addresses.map(_ -> Balance(50L)))

  def generateSnapshotLastAccTxRefs(
    addressTxs: Map[Address, Hashed[Transaction]]
  ): SortedMap[Address, TransactionReference] =
    SortedMap.from(
      addressTxs.map {
        case (address, transaction) =>
          address -> TransactionReference(transaction.ordinal, transaction.hash)
      }
    )

  def generateSnapshot(peerId: PeerId): GlobalIncrementalSnapshot =
    GlobalIncrementalSnapshot(
      snapshotOrdinal10,
      snapshotHeight6,
      snapshotSubHeight0,
      lastSnapshotHash,
      SortedSet.empty,
      SortedMap.empty,
      SortedSet.empty,
      EpochProgress.MinValue,
      NonEmptyList.one(peerId),
      SnapshotTips(SortedSet.empty, SortedSet.empty),
      GlobalSnapshotStateProof(Hash.empty, Hash.empty, Hash.empty, None)
    )

  def generateGlobalSnapshotInfo: GlobalSnapshotInfo = GlobalSnapshotInfo.empty

  test("download should happen for the base no blocks case") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            _,
            srcKey,
            _,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR,
            _,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(4L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(5L), ProofsHash("parent2"))

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 1)
          block = SHIBlock(NonEmptyList.one(parent2), NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed)))
          hashedBlock <- forAsyncKryo(block, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs.head))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId)
              .copy(
                blocks = SortedSet(BlockAsActiveTip(hashedBlock.signed, NonNegLong.MinValue)),
                tips = SnapshotTips(
                  SortedSet(DeprecatedTip(parent1, snapshotOrdinal8)),
                  SortedSet(ActiveTip(parent2, 2L, snapshotOrdinal9))
                )
              ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances, SortedMap.empty, SortedMap.empty)
          balancesBefore <- balancesR.get
          blocksBefore <- blocksR.toMap
          lastGlobalSnapshotBefore <- lastSnapR.get
          lastAcceptedTxRBefore <- lastAccTxR.toMap

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect
            .same(
              (
                processingResult,
                balancesBefore,
                balancesAfter,
                blocksBefore,
                blocksAfter,
                lastGlobalSnapshotBefore,
                lastGlobalSnapshotAfter,
                lastAcceptedTxRBefore,
                lastAcceptedTxRAfter
              ),
              (
                DownloadPerformed(
                  SnapshotReference(
                    snapshotHeight6,
                    snapshotSubHeight0,
                    snapshotOrdinal10,
                    lastSnapshotHash,
                    hashedSnapshot.hash,
                    hashedSnapshot.proofsHash
                  ),
                  Set(hashedBlock.proofsHash),
                  Set.empty
                ),
                Map.empty,
                snapshotBalances,
                Map.empty,
                Map(
                  hashedBlock.proofsHash -> MajorityBlock(
                    BlockReference(hashedBlock.height, hashedBlock.proofsHash),
                    NonNegLong.MinValue,
                    Active
                  ),
                  parent1.hash -> MajorityBlock(parent1, 0L, Deprecated),
                  parent2.hash -> MajorityBlock(parent2, 2L, Active)
                ),
                None,
                Some((hashedSnapshot, snapshotInfo)),
                Map.empty,
                snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
              )
            )
    }
  }

  test("download should happen for the case when there are waiting blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR,
            _,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))

        val hashedSHIBlock = hashedSHIBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 7).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          aboveRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          nonMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal9),
                  DeprecatedTip(
                    BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                    snapshotOrdinal9
                  )
                ),
                SortedSet(
                  ActiveTip(parent1, 1L, snapshotOrdinal8),
                  ActiveTip(
                    BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                    1L,
                    snapshotOrdinal9
                  ),
                  ActiveTip(
                    BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                    2L,
                    snapshotOrdinal9
                  )
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances, SortedMap.empty, SortedMap.empty)

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](aboveRangeBlock.signed).some)
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](nonMajorityInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](majorityInRangeBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(
            WaitingBlock[SHIBlock](majorityAboveRangeActiveTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(
            WaitingBlock[SHIBlock](majorityInRangeDeprecatedTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(
            WaitingBlock[SHIBlock](majorityInRangeActiveTipBlock.signed).some
          )

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight0,
                  snapshotOrdinal10,
                  lastSnapshotHash,
                  hashedSnapshot.hash,
                  hashedSnapshot.proofsHash
                ),
                Set(majorityInRangeBlock.proofsHash),
                Set(nonMajorityInRangeBlock.proofsHash)
              ),
              Map(
                aboveRangeBlock.proofsHash -> WaitingBlock[SHIBlock](aboveRangeBlock.signed),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityAboveRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityInRangeDeprecatedTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                  0L,
                  Deprecated
                ),
                majorityInRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                  2L,
                  Active
                ),
                parent1.hash -> MajorityBlock(parent1, 1L, Active),
                parent3.hash -> MajorityBlock(parent3, 0L, Deprecated)
              ),
              snapshotBalances,
              Some((hashedSnapshot, snapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("download should happen for the case when there are postponed blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR,
            _,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(8L), ProofsHash("parent5"))

        val hashedSHIBlock = hashedSHIBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          aboveRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent5),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          aboveRangeRelatedToTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          nonMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          aboveRangeRelatedBlock <- hashedSHIBlock(
            NonEmptyList.one(BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash)),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedSHIBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal9),
                  DeprecatedTip(
                    BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                    snapshotOrdinal9
                  )
                ),
                SortedSet(
                  ActiveTip(parent1, 1L, snapshotOrdinal8),
                  ActiveTip(
                    BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                    1L,
                    snapshotOrdinal9
                  ),
                  ActiveTip(
                    BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                    2L,
                    snapshotOrdinal9
                  )
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances, SortedMap.empty, SortedMap.empty)

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(PostponedBlock[SHIBlock](aboveRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedToTipBlock.proofsHash).set(
            PostponedBlock[SHIBlock](aboveRangeRelatedToTipBlock.signed).some
          )
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(
            PostponedBlock[SHIBlock](nonMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(PostponedBlock[SHIBlock](majorityInRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedBlock.proofsHash).set(PostponedBlock[SHIBlock](aboveRangeRelatedBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(
            PostponedBlock[SHIBlock](majorityAboveRangeActiveTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(
            PostponedBlock[SHIBlock](majorityInRangeDeprecatedTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(
            PostponedBlock[SHIBlock](majorityInRangeActiveTipBlock.signed).some
          )

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight0,
                  snapshotOrdinal10,
                  lastSnapshotHash,
                  hashedSnapshot.hash,
                  hashedSnapshot.proofsHash
                ),
                Set(majorityInRangeBlock.proofsHash),
                Set(nonMajorityInRangeBlock.proofsHash)
              ),
              Map(
                aboveRangeBlock.proofsHash -> PostponedBlock[SHIBlock](aboveRangeBlock.signed),
                aboveRangeRelatedToTipBlock.proofsHash -> WaitingBlock[SHIBlock](aboveRangeRelatedToTipBlock.signed),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                aboveRangeRelatedBlock.proofsHash -> WaitingBlock[SHIBlock](aboveRangeRelatedBlock.signed),
                majorityAboveRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityInRangeDeprecatedTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                  0L,
                  Deprecated
                ),
                majorityInRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                  2L,
                  Active
                ),
                parent1.hash -> MajorityBlock(parent1, 1L, Active),
                parent3.hash -> MajorityBlock(parent3, 0L, Deprecated)
              ),
              snapshotBalances,
              Some((hashedSnapshot, snapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("alignment at same height should happen when snapshot with new ordinal but known height is processed") {
    testResources.use {
      case (snapshotProcessor, sp, kp, _, srcKey, _, _, _, peerId, balancesR, blocksR, lastSnapR, lastAccTxR, _, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              subHeight = snapshotSubHeight1,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)

          processingResult <- snapshotProcessor.process(
            hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
          )

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, balancesAfter, blocksAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight1,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                Set.empty
              ),
              Map.empty,
              Map.empty,
              Some((hashedNextSnapshot, GlobalSnapshotInfo.empty)),
              Map.empty
            )
          )
    }
  }

  test("alignment at new height should happen when node is aligned with the majority in processed snapshot") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR,
            transactionStorage,
            globalSnapshotContextFns
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val parent12 = BlockReference(Height(6L), ProofsHash("parent12"))
        val parent22 = BlockReference(Height(7L), ProofsHash("parent22"))
        val parent32 = BlockReference(Height(8L), ProofsHash("parent32"))
        val parent42 = BlockReference(Height(9L), ProofsHash("parent42"))
        val parent52 = BlockReference(Height(9L), ProofsHash("parent52"))

        val hashedSHIBlock = hashedSHIBlockForKeyPair(keys)

        val snapshotBalances = generateSnapshotBalances(Set(srcAddress))
        val snapshotInfo = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, snapshotBalances, SortedMap.empty, SortedMap.empty)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 5).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 2).map(_.toList)

          waitingInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          postponedInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          majorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          inRangeRelatedTxnBlock <- hashedSHIBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeAcceptedBlock <- hashedSHIBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          aboveRangeMajorityBlock <- hashedSHIBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          waitingAboveRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          postponedAboveRangeRelatedToTipBlock <- hashedSHIBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedAboveRangeRelatedTxnBlock <- hashedSHIBlock(
            NonEmptyList.of(parent5, parent52),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )

          _ <- majorityInRangeBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(transactionStorage.accept))
          _ <- aboveRangeMajorityBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(transactionStorage.accept))
          _ <- aboveRangeAcceptedBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(transactionStorage.accept))

          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal9),
                  DeprecatedTip(parent2, snapshotOrdinal9),
                  DeprecatedTip(parent12, snapshotOrdinal9),
                  DeprecatedTip(parent22, snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent3, 1L, snapshotOrdinal8),
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal8),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              height = snapshotHeight8,
              lastSnapshotHash = hashedLastSnapshot.hash,
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, 1L), BlockAsActiveTip(aboveRangeMajorityBlock.signed, 2L)),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal11),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          newSnapshotInfo <- globalSnapshotContextFns.createContext(
            snapshotInfo,
            hashedLastSnapshot.signed.value,
            hashedNextSnapshot.signed
          )
          _ <- lastSnapR.set((hashedLastSnapshot, snapshotInfo).some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock[SHIBlock](parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock[SHIBlock](parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock[SHIBlock](parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock[SHIBlock](parent4, 1L, Active).some)

          _ <- blocksR(parent12.hash).set(MajorityBlock[SHIBlock](parent12, 2L, Deprecated).some)
          _ <- blocksR(parent22.hash).set(MajorityBlock[SHIBlock](parent22, 2L, Deprecated).some)
          _ <- blocksR(parent32.hash).set(MajorityBlock[SHIBlock](parent32, 1L, Active).some)
          _ <- blocksR(parent42.hash).set(MajorityBlock[SHIBlock](parent42, 1L, Active).some)
          _ <- blocksR(parent52.hash).set(MajorityBlock[SHIBlock](parent52, 1L, Active).some)
          // Inserting blocks in required state
          _ <- blocksR(waitingInRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](waitingInRangeBlock.signed).some)
          _ <- blocksR(postponedInRangeBlock.proofsHash).set(PostponedBlock[SHIBlock](postponedInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(AcceptedBlock[SHIBlock](majorityInRangeBlock).some)
          _ <- blocksR(inRangeRelatedTxnBlock.proofsHash).set(PostponedBlock[SHIBlock](inRangeRelatedTxnBlock.signed).some)
          _ <- blocksR(aboveRangeAcceptedBlock.proofsHash).set(AcceptedBlock[SHIBlock](aboveRangeAcceptedBlock).some)
          _ <- blocksR(aboveRangeMajorityBlock.proofsHash).set(AcceptedBlock[SHIBlock](aboveRangeMajorityBlock).some)
          _ <- blocksR(waitingAboveRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](waitingAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeRelatedToTipBlock.proofsHash).set(
            PostponedBlock[SHIBlock](postponedAboveRangeRelatedToTipBlock.signed).some
          )
          _ <- blocksR(postponedAboveRangeRelatedTxnBlock.proofsHash).set(
            PostponedBlock[SHIBlock](postponedAboveRangeRelatedTxnBlock.signed).some
          )

          processingResult <- snapshotProcessor.process(
            hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
          )

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
          lastTxRef <- TransactionReference.of[IO, SHITransaction](aboveRangeAcceptedBlock.signed.value.transactions.head)
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                SnapshotReference(
                  snapshotHeight8,
                  snapshotSubHeight0,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                Set(waitingInRangeBlock.proofsHash, postponedInRangeBlock.proofsHash, inRangeRelatedTxnBlock.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 1L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                parent32.hash -> MajorityBlock(parent32, 1L, Active),
                parent42.hash -> MajorityBlock(parent42, 1L, Active),
                parent52.hash -> MajorityBlock(parent52, 1L, Active),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                aboveRangeAcceptedBlock.proofsHash -> AcceptedBlock[SHIBlock](aboveRangeAcceptedBlock),
                aboveRangeMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeMajorityBlock.height, aboveRangeMajorityBlock.proofsHash),
                  2L,
                  Active
                ),
                waitingAboveRangeBlock.proofsHash -> WaitingBlock[SHIBlock](waitingAboveRangeBlock.signed),
                // inRangeRelatedTxnBlock.proofsHash -> WaitingBlock(inRangeRelatedTxnBlock.signed),
                postponedAboveRangeRelatedToTipBlock.proofsHash -> PostponedBlock[SHIBlock](
                  postponedAboveRangeRelatedToTipBlock.signed
                ),
                postponedAboveRangeRelatedTxnBlock.proofsHash -> WaitingBlock[SHIBlock](
                  postponedAboveRangeRelatedTxnBlock.signed
                )
              ),
              Map.empty,
              Some((hashedNextSnapshot, newSnapshotInfo)),
              Map(srcAddress -> Accepted(lastTxRef))
            )
          )
    }
  }

  test("redownload should happen when node is misaligned with majority in processed snapshot") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR,
            transactionStorage,
            globalSnapshotContextFns
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val parent12 = BlockReference(Height(6L), ProofsHash("parent12"))
        val parent22 = BlockReference(Height(7L), ProofsHash("parent22"))
        val parent32 = BlockReference(Height(8L), ProofsHash("parent32"))
        val parent42 = BlockReference(Height(9L), ProofsHash("parent42"))
        val parent52 = BlockReference(Height(9L), ProofsHash("parent52"))

        val hashedSHIBlock = hashedSHIBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 4).map(_.toList)

          waitingInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(2).signed))
          )
          postponedInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(3).signed))
          )
          waitingMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          acceptedMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          majorityUnknownBlock <- hashedSHIBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          acceptedNonMajorityInRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          aboveRangeAcceptedBlock <- hashedSHIBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          aboveRangeAcceptedMajorityBlock <- hashedSHIBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeUnknownMajorityBlock <- hashedSHIBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          waitingAboveRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          postponedAboveRangeBlock <- hashedSHIBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          postponedAboveRangeNotRelatedBlock <- hashedSHIBlock(
            NonEmptyList.of(parent5, parent52),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal9),
                  DeprecatedTip(parent2, snapshotOrdinal9),
                  DeprecatedTip(parent12, snapshotOrdinal9),
                  DeprecatedTip(parent22, snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent3, 1L, snapshotOrdinal8),
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal8),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              height = snapshotHeight8,
              lastSnapshotHash = hashedLastSnapshot.hash,
              blocks = SortedSet(
                BlockAsActiveTip(waitingMajorityInRangeBlock.signed, 1L),
                BlockAsActiveTip(postponedMajorityInRangeBlock.signed, 1L),
                BlockAsActiveTip(acceptedMajorityInRangeBlock.signed, 2L),
                BlockAsActiveTip(majorityUnknownBlock.signed, 1L),
                BlockAsActiveTip(aboveRangeAcceptedMajorityBlock.signed, 0L),
                BlockAsActiveTip(aboveRangeUnknownMajorityBlock.signed, 0L)
              ),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal8),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, snapshotBalances, SortedMap.empty, SortedMap.empty)
          newSnapshotInfo <- globalSnapshotContextFns.createContext(
            snapshotInfo,
            hashedLastSnapshot.signed.value,
            hashedNextSnapshot.signed
          )
          _ <- lastSnapR.set((hashedLastSnapshot, snapshotInfo).some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock[SHIBlock](parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock[SHIBlock](parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock[SHIBlock](parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock[SHIBlock](parent4, 1L, Active).some)

          _ <- blocksR(parent12.hash).set(MajorityBlock[SHIBlock](parent12, 2L, Deprecated).some)
          _ <- blocksR(parent22.hash).set(MajorityBlock[SHIBlock](parent22, 2L, Deprecated).some)
          _ <- blocksR(parent32.hash).set(MajorityBlock[SHIBlock](parent32, 2L, Active).some)
          _ <- blocksR(parent42.hash).set(MajorityBlock[SHIBlock](parent42, 1L, Active).some)
          _ <- blocksR(parent52.hash).set(MajorityBlock[SHIBlock](parent52, 1L, Active).some)
          // Inserting blocks in required state
          _ <- blocksR(waitingInRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](waitingInRangeBlock.signed).some)
          _ <- blocksR(postponedInRangeBlock.proofsHash).set(PostponedBlock[SHIBlock](postponedInRangeBlock.signed).some)
          _ <- blocksR(waitingMajorityInRangeBlock.proofsHash).set(
            WaitingBlock[SHIBlock](waitingMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(postponedMajorityInRangeBlock.proofsHash).set(
            PostponedBlock[SHIBlock](postponedMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(acceptedMajorityInRangeBlock.proofsHash).set(
            AcceptedBlock[SHIBlock](acceptedMajorityInRangeBlock).some
          )
          _ <- blocksR(acceptedNonMajorityInRangeBlock.proofsHash).set(
            AcceptedBlock[SHIBlock](acceptedNonMajorityInRangeBlock).some
          )
          _ <- blocksR(aboveRangeAcceptedBlock.proofsHash).set(AcceptedBlock[SHIBlock](aboveRangeAcceptedBlock).some)
          _ <- blocksR(aboveRangeAcceptedMajorityBlock.proofsHash).set(
            AcceptedBlock[SHIBlock](aboveRangeAcceptedMajorityBlock).some
          )
          _ <- blocksR(waitingAboveRangeBlock.proofsHash).set(WaitingBlock[SHIBlock](waitingAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeBlock.proofsHash).set(
            PostponedBlock[SHIBlock](postponedAboveRangeBlock.signed).some
          )
          _ <- blocksR(postponedAboveRangeNotRelatedBlock.proofsHash).set(
            PostponedBlock[SHIBlock](postponedAboveRangeNotRelatedBlock.signed).some
          )

          processingResult <- snapshotProcessor.process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              RedownloadPerformed(
                SnapshotReference(
                  snapshotHeight8,
                  snapshotSubHeight0,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                addedBlocks = Set(
                  waitingMajorityInRangeBlock.proofsHash,
                  postponedMajorityInRangeBlock.proofsHash,
                  majorityUnknownBlock.proofsHash,
                  aboveRangeUnknownMajorityBlock.proofsHash
                ),
                removedBlocks = Set(acceptedNonMajorityInRangeBlock.proofsHash),
                removedObsoleteBlocks = Set(waitingInRangeBlock.proofsHash, postponedInRangeBlock.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 2L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                parent32.hash -> MajorityBlock(parent32, 3L, Active),
                parent42.hash -> MajorityBlock(parent42, 1L, Active),
                parent52.hash -> MajorityBlock(parent52, 1L, Active),
                waitingMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(waitingMajorityInRangeBlock.height, waitingMajorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                postponedMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(postponedMajorityInRangeBlock.height, postponedMajorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                acceptedMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(acceptedMajorityInRangeBlock.height, acceptedMajorityInRangeBlock.proofsHash),
                  2L,
                  Active
                ),
                majorityUnknownBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityUnknownBlock.height, majorityUnknownBlock.proofsHash),
                  1L,
                  Active
                ),
                aboveRangeAcceptedBlock.proofsHash -> WaitingBlock[SHIBlock](aboveRangeAcceptedBlock.signed),
                aboveRangeAcceptedMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeAcceptedMajorityBlock.height, aboveRangeAcceptedMajorityBlock.proofsHash),
                  0L,
                  Active
                ),
                aboveRangeUnknownMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeUnknownMajorityBlock.height, aboveRangeUnknownMajorityBlock.proofsHash),
                  0L,
                  Active
                ),
                waitingAboveRangeBlock.proofsHash -> WaitingBlock[SHIBlock](waitingAboveRangeBlock.signed),
                postponedAboveRangeBlock.proofsHash -> WaitingBlock[SHIBlock](postponedAboveRangeBlock.signed),
                postponedAboveRangeNotRelatedBlock.proofsHash -> PostponedBlock[SHIBlock](
                  postponedAboveRangeNotRelatedBlock.signed
                )
              ),
              newSnapshotInfo.balances,
              Some((hashedNextSnapshot, newSnapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("snapshot should be ignored when a snapshot pushed for processing is not a next one") {
    testResources.use {
      case (snapshotProcessor, sp, kp, _, srcKey, _, _, _, peerId, _, _, lastSnapR, _, _, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal12,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)
          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Right(
                SnapshotIgnored(
                  SnapshotReference.fromHashedSnapshot(hashedNextSnapshot)
                )
              ),
              (hashedLastSnapshot, GlobalSnapshotInfo.empty)
            )
          )
    }
  }

  test("error should be thrown when the tips get misaligned") {
    testResources.use {
      case (snapshotProcessor, sp, kp, _, srcKey, _, _, _, peerId, _, blocksR, lastSnapR, _, _, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(9L), ProofsHash("parent2"))

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              height = snapshotHeight8,
              lastSnapshotHash = hashedLastSnapshot.hash,
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent2, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)
          // Inserting tips
          _ <- blocksR(parent2.hash).set(MajorityBlock[SHIBlock](parent2, 1L, Active).some)

          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Left(TipsGotMisaligned(Set(parent1.hash), Set.empty)),
              (hashedLastSnapshot, GlobalSnapshotInfo.empty)
            )
          )
    }
  }

  private def hashedSHIBlockForKeyPair(keys: (KeyPair, KeyPair, KeyPair))(implicit sc: SecurityProvider[IO], ks: KryoSerializer[IO]) =
    (parent: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[SHITransaction]]) =>
      (
        forAsyncKryo(SHIBlock(parent, transactions), keys._1),
        forAsyncKryo(SHIBlock(parent, transactions), keys._2),
        forAsyncKryo(SHIBlock(parent, transactions), keys._3)
      ).tupled.flatMap {
        case (b1, b2, b3) =>
          b1.addProof(b2.proofs.head).addProof(b3.proofs.head).toHashedWithSignatureCheck.map(_.toOption.get)
      }
}
