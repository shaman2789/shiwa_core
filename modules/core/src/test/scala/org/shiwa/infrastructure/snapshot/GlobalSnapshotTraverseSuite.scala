package org.shiwa.dag.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.foldable._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.shiwa.ext.cats.effect.ResourceIO
import org.shiwa.ext.cats.syntax.next._
import org.shiwa.infrastructure.snapshot._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.{SHITransaction, TransactionReference}
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.shiwa.sdk.infrastructure.block.processing.{BlockAcceptanceLogic, BlockAcceptanceManager, BlockValidator}
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot._
import org.shiwa.sdk.modules.SdkValidators
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.hex.Hex
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.{Signed, SignedValidator}
import org.shiwa.security.{Hashed, KeyPairGenerator, SecurityProvider}
import org.shiwa.shared.sharedKryoRegistrar
import org.shiwa.syntax.sortedCollection._
import org.shiwa.tools.TransactionGenerator._
import org.shiwa.tools.{SHIBlockGenerator, TransactionGenerator}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object GlobalSnapshotTraverseSuite extends MutableIOSuite with Checkers {
  type GenKeyPairFn = () => KeyPair

  type Res = (KryoSerializer[IO], SecurityProvider[IO], Metrics[IO], Random[IO])

  override def sharedResource: Resource[IO, Res] = for {
    kryo <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
    random <- Random.scalaUtilRandom[IO].asResource
  } yield (kryo, sp, metrics, random)

  val balances: Map[Address, Balance] = Map(Address("SHI8Yy2enxizZdWoipKKZg6VXwk7rY2Z54mJqUdC") -> Balance(NonNegLong(10L)))

  def mkSnapshots(dags: List[List[BlockAsActiveTip[SHIBlock]]], initBalances: Map[Address, Balance])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ): IO[(Hashed[GlobalSnapshot], NonEmptyList[Hashed[GlobalIncrementalSnapshot]])] =
    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      Signed
        .forAsyncKryo[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(initBalances, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
        .flatMap { genesis =>
          GlobalIncrementalSnapshot.fromGlobalSnapshot(genesis).flatMap { incremental =>
            mkSnapshot(genesis.hash, incremental, keyPair, SortedSet.empty).flatMap { snap1 =>
              dags
                .foldLeftM(NonEmptyList.of(snap1)) {
                  case (snapshots, blocksChunk) =>
                    mkSnapshot(snapshots.head.hash, snapshots.head.signed.value, keyPair, blocksChunk.toSortedSet).map(snapshots.prepend)
                }
                .map(incrementals => (genesis, incrementals.reverse))
            }
          }
        }
    }

  def mkSnapshot(lastHash: Hash, reference: GlobalIncrementalSnapshot, keyPair: KeyPair, blocks: SortedSet[BlockAsActiveTip[SHIBlock]])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO]
  ) =
    for {
      activeTips <- reference.activeTips
      snapshot = GlobalIncrementalSnapshot(
        reference.ordinal.next,
        Height.MinValue,
        SubHeight.MinValue,
        lastHash,
        blocks.toSortedSet,
        SortedMap.empty,
        SortedSet.empty,
        reference.epochProgress,
        NonEmptyList.of(PeerId(Hex("peer1"))),
        reference.tips.copy(remainedActive = activeTips),
        reference.stateProof
      )
      signed <- Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](snapshot, keyPair)
      hashed <- signed.toHashed
    } yield hashed

  type SHIS = (List[Address], Long, SortedMap[Address, Signed[SHITransaction]], List[List[BlockAsActiveTip[SHIBlock]]])

  def mkBlocks(feeValue: NonNegLong, numberOfAddresses: Int, txnsChunksRanges: List[(Int, Int)], blocksChunksRanges: List[(Int, Int)])(
    implicit K: KryoSerializer[IO],
    S: SecurityProvider[IO],
    R: Random[IO]
  ): IO[SHIS] = for {
    keyPairs <- (1 to numberOfAddresses).toList.traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    addressParams = keyPairs.map(keyPair => AddressParams(keyPair))
    addresses = keyPairs.map(_.getPublic.toAddress)
    txnsSize = if (txnsChunksRanges.nonEmpty) txnsChunksRanges.map(_._2).max.toLong else 0
    txns <- TransactionGenerator
      .infiniteTransactionStream[IO](PosInt.unsafeFrom(1), feeValue, NonEmptyList.fromListUnsafe(addressParams))
      .take(txnsSize)
      .compile
      .toList
    lastTxns = txns.groupBy(_.source).view.mapValues(_.last).toMap.toSortedMap
    transactionsChain = txnsChunksRanges
      .foldLeft[List[List[Signed[SHITransaction]]]](Nil) { case (acc, (start, end)) => txns.slice(start, end) :: acc }
      .map(txns => NonEmptySet.fromSetUnsafe(SortedSet.from(txns)))
      .reverse
    blockSigningKeyPairs <- NonEmptyList.of("", "", "").traverse(_ => KeyPairGenerator.makeKeyPair[IO])
    dags <- SHIBlockGenerator.createSHIs(transactionsChain, initialReferences(), blockSigningKeyPairs).compile.toList
    chaunkedDags = blocksChunksRanges
      .foldLeft[List[List[BlockAsActiveTip[SHIBlock]]]](Nil) { case (acc, (start, end)) => dags.slice(start, end) :: acc }
      .reverse
  } yield (addresses, txnsSize, lastTxns, chaunkedDags)

  def gst(
    globalSnapshot: Hashed[GlobalSnapshot],
    incrementalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    rollbackHash: Hash
  )(implicit K: KryoSerializer[IO], S: SecurityProvider[IO]) = {
    def loadGlobalSnapshot(hash: Hash): IO[Option[Signed[GlobalSnapshot]]] =
      hash match {
        case h if h === globalSnapshot.hash => Some(globalSnapshot.signed).pure[IO]
        case _                              => None.pure[IO]
      }
    def loadGlobalIncrementalSnapshot(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
      hash match {
        case h if h =!= globalSnapshot.hash =>
          Some(incrementalSnapshots.map(snapshot => (snapshot.hash, snapshot)).toMap.get(hash).get.signed).pure[IO]
        case _ => None.pure[IO]
      }

    val signedValidator = SignedValidator.make[IO]
    val blockValidator =
      BlockValidator.make[IO, SHITransaction, SHIBlock](
        signedValidator,
        TransactionChainValidator.make[IO, SHITransaction],
        TransactionValidator.make[IO, SHITransaction](signedValidator)
      )
    val blockAcceptanceManager = BlockAcceptanceManager.make(BlockAcceptanceLogic.make[IO, SHITransaction, SHIBlock], blockValidator)
    val stateChannelValidator = StateChannelValidator.make[IO](signedValidator, Some(Set.empty[Address]))
    val validators = SdkValidators.make[IO](None, Some(Set.empty[Address]))
    val currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
      BlockAcceptanceManager.make[IO, CurrencyTransaction, CurrencyBlock](validators.currencyBlockValidator),
      Amount(0L)
    )
    val currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotAcceptanceManager)
    val stateChannelProcessor = GlobalSnapshotStateChannelEventsProcessor.make[IO](stateChannelValidator, currencySnapshotContextFns)
    val snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make[IO](blockAcceptanceManager, stateChannelProcessor, Amount.empty)
    val snapshotContextFunctions = GlobalSnapshotContextFunctions.make[IO](snapshotAcceptanceManager)
    GlobalSnapshotTraverse.make[IO](loadGlobalIncrementalSnapshot, loadGlobalSnapshot, snapshotContextFunctions, rollbackHash)
  }

  test("can compute state for given incremental global snapshot") { res =>
    implicit val (kryo, sp, metrics, _) = res

    mkSnapshots(List.empty, balances).flatMap { snapshots =>
      gst(snapshots._1, snapshots._2.toList, snapshots._2.head.hash).loadChain()
    }.map(state =>
      expect.eql(GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.from(balances), SortedMap.empty, SortedMap.empty), state._1)
    )
  }

  test("computed state contains last refs and preserve total amount of balances when no fees or rewards ") { res =>
    implicit val (kryo, sp, metrics, random) = res

    forall(dagBlockChainGen()) { output: IO[SHIS] =>
      for {
        (addresses, _, lastTxns, chunkedDags) <- output
        (global, incrementals) <- mkSnapshots(
          chunkedDags,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(global, incrementals.toList, incrementals.last.hash)
        (info, _) <- traverser.loadChain()
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L))), (lastTxRefs, totalBalance))

    }
  }

  test("computed state contains last refs and include fees in total amount of balances") { res =>
    implicit val (kryo, sp, metrics, random) = res

    forall(dagBlockChainGen(1L)) { output: IO[SHIS] =>
      for {
        (addresses, txnsSize, lastTxns, chunkedDags) <- output
        (global, incrementals) <- mkSnapshots(
          chunkedDags,
          addresses.map(address => address -> Balance(NonNegLong(1000L))).toMap
        )
        traverser = gst(global, incrementals.toList, incrementals.last.hash)
        (info, _) <- traverser.loadChain()
        totalBalance = info.balances.values.map(Balance.toAmount(_)).reduce(_.plus(_).toOption.get)
        lastTxRefs <- lastTxns.traverse(TransactionReference.of(_))
      } yield
        expect.eql((info.lastTxRefs, Amount(NonNegLong.unsafeFrom(addresses.size * 1000L - txnsSize * 1L))), (lastTxRefs, totalBalance))

    }
  }

  private def initialReferences() =
    NonEmptyList.fromListUnsafe(
      List
        .range(0, 4)
        .map { i =>
          BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i)))
        }
    )

  private def dagBlockChainGen(
    feeValue: NonNegLong = 0L
  )(implicit r: Random[IO], ks: KryoSerializer[IO], sc: SecurityProvider[IO]): Gen[IO[SHIS]] = for {
    numberOfAddresses <- Gen.choose(2, 5)
    txnsChunksRanges <- Gen
      .listOf(Gen.choose(0, 50))
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
    blocksChunksRanges <- Gen
      .const((0 to txnsChunksRanges.size).toList)
      .map(l => (0 :: l).distinct.sorted)
      .map(list => list.zip(list.tail))
  } yield mkBlocks(feeValue, numberOfAddresses, txnsChunksRanges, blocksChunksRanges)

}
