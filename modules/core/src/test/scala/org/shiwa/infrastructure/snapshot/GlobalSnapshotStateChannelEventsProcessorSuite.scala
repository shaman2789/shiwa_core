package org.shiwa.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.validated._

import scala.collection.immutable.SortedMap

import org.shiwa.currency.schema.currency._
import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.GlobalSnapshotInfo
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Amount
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.shiwa.sdk.infrastructure.snapshot.{
  CurrencySnapshotAcceptanceManager,
  CurrencySnapshotContextFunctions,
  GlobalSnapshotStateChannelEventsProcessor
}
import org.shiwa.sdk.modules.SdkValidators
import org.shiwa.security.hash.Hash
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.Signed.forAsyncKryo
import org.shiwa.security.{KeyPairGenerator, SecurityProvider}
import org.shiwa.shared.sharedKryoRegistrar
import org.shiwa.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStateChannelEventsProcessorSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  def mkProcessor(
    stateChannelSeedlist: Set[Address],
    failed: Option[(Address, StateChannelValidator.StateChannelValidationError)] = None
  )(implicit K: KryoSerializer[IO], S: SecurityProvider[IO]) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
    }
    val validators = SdkValidators.make[IO](None, Some(stateChannelSeedlist))
    val currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
      BlockAcceptanceManager.make[IO, CurrencyTransaction, CurrencyBlock](validators.currencyBlockValidator),
      Amount(0L)
    )
    val currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotAcceptanceManager)
    GlobalSnapshotStateChannelEventsProcessor.make[IO](validator, currencySnapshotContextFns)
  }

  test("return new sc event") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output <- mkStateChannelOutput(keyPair)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(Set(address))
      expected = (
        SortedMap((address, NonEmptyList.one(output.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output :: Nil)
    } yield expect.same(expected, result)

  }

  test("return two dependent sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshotBinary.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(Set(address))
      expected = (
        SortedMap((address, NonEmptyList.of(output2.snapshotBinary, output1.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc event when reference to last state channel snapshot hash is correct") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output1Hash <- output1.snapshotBinary.hashF
      output2 <- mkStateChannelOutput(keyPair, Some(output1Hash))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((address, output1Hash)))
      service = mkProcessor(Set(address))
      expected = (
        SortedMap((address, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return no sc events when reference to last state channel snapshot hash is incorrect") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes("incorrect".getBytes())))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((address, Hash.fromBytes(output1.snapshotBinary.content))))
      service = mkProcessor(Set(address))
      expected = (
        SortedMap.empty[Address, NonEmptyList[StateChannelSnapshotBinary]],
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc events for different addresses") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(Set(address1, address2))
      expected = (
        SortedMap((address1, NonEmptyList.of(output1.snapshotBinary)), (address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return only valid sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(
        Set(address1, address2),
        Some(address1 -> StateChannelValidator.NotSignedExclusivelyByStateChannelOwner)
      )
      expected = (
        SortedMap((address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  def mkStateChannelOutput(keyPair: KeyPair, hash: Option[Hash] = None)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    content <- Random.scalaUtilRandom[IO].flatMap(_.nextString(10))
    binary <- StateChannelSnapshotBinary(hash.getOrElse(Hash.empty), content.getBytes, SnapshotFee.MinValue).pure[IO]
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(lastStateChannelSnapshotHashes, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)

}