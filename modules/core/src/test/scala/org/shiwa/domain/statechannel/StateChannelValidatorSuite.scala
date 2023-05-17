package org.shiwa.domain.statechannel

import cats.data.NonEmptySet
import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.option._
import cats.syntax.validated._

import scala.collection.immutable.SortedSet

import org.shiwa.currency.schema.currency.SnapshotFee
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.ID.Id
import org.shiwa.schema.address.Address
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.security.hash.Hash
import org.shiwa.security.hex.Hex
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.Signed.forAsyncKryo
import org.shiwa.security.signature.SignedValidator.InvalidSignatures
import org.shiwa.security.signature.signature.{Signature, SignatureProof}
import org.shiwa.security.signature.{Signed, SignedValidator}
import org.shiwa.security.{KeyPairGenerator, SecurityProvider}
import org.shiwa.shared.sharedKryoRegistrar
import org.shiwa.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object StateChannelValidatorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, StateChannelServiceSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  private val testStateChannel = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)

  test("should succeed when state channel is signed correctly and binary size is within allowed maximum") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(Set(address).some)
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)

  }

  test("should fail when the signature is wrong") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair2.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair2.getPublic.toId))
          )
        )
      )
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(Set(address).some)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
        result
      )
  }

  test("should fail when the signature doesn't match address") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair2.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(Set(address).some)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  test("should fail when there is more than one signature") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair1.getPublic.toAddress
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1)
      doubleSigned <- signedSCBinary.signAlsoWith(keyPair2)
      scOutput = StateChannelOutput(address, doubleSigned)
      validator = mkValidator(Set(address).some)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  test("should fail when binary size exceeds max allowed size") { res =>
    implicit val (kryo, sp) = res

    val signedSCBinary = Signed(
      testStateChannel,
      NonEmptySet.fromSetUnsafe(
        SortedSet(
          SignatureProof(
            Id(
              Hex(
                "c4960e903a9d05662b83332a9ee7059ec214e6d587ae5d80f97924bb1519be7ed60116887ce90cff6134697df3faebdfb6a6a04c06a7270b90685532d2fa85e1"
              )
            ),
            Signature(
              Hex(
                "304402201cf4a09b3a693f2627ca94df9715bb8b119c8518e79128b88d4d6531f01dac5502204f377d700ebb8f336f8eedb1a9dde9f2aacca4132612d6528aea4ec2570d89f3"
              )
            )
          )
        )
      )
    )
    val address = Address("SHI7EJu17WPtbKMP5kNBWGpp3iVtmNwDeS6E4ge8")
    val scOutput = StateChannelOutput(address, signedSCBinary)
    val validator = mkValidator(Set(address).some, maxBinarySizeInBytes = 443)

    validator
      .validate(scOutput)
      .map(
        expect.same(
          StateChannelValidator.BinaryExceedsMaxAllowedSize(443, 444).invalidNec,
          _
        )
      )
  }

  test("should fail when the state channel address is not on the seedlist") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(Set.empty[Address].some)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.StateChannelAddressNotAllowed(address).invalidNec,
        result
      )
  }

  test("should succeed when the state channel seedlist check is disabled") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None)
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  private def mkValidator(stateChannelSeedlist: Option[Set[Address]], maxBinarySizeInBytes: Long = 1024)(
    implicit S: SecurityProvider[IO],
    K: KryoSerializer[IO]
  ) =
    StateChannelValidator.make[IO](SignedValidator.make[IO], stateChannelSeedlist, maxBinarySizeInBytes)

}