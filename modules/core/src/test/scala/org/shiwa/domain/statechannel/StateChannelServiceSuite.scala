package org.shiwa.domain.statechannel

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.effect.std.Queue
import cats.syntax.validated._

import org.shiwa.currency.schema.currency.SnapshotFee
import org.shiwa.domain.cell.L0Cell
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.security.hash.Hash
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.Signed.forAsyncKryo
import org.shiwa.security.{KeyPairGenerator, SecurityProvider}
import org.shiwa.shared.sharedKryoRegistrar
import org.shiwa.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object StateChannelServiceSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, StateChannelServiceSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  test("state channel output processed successfully") { res =>
    implicit val (kryo, sp) = res

    for {
      output <- mkStateChannelOutput()
      service <- mkService()
      result <- service.process(output)
    } yield expect.same(Right(()), result)

  }

  test("state channel output failed on validation") { res =>
    implicit val (kryo, sp) = res

    for {
      output <- mkStateChannelOutput()
      expected = StateChannelValidator.NotSignedExclusivelyByStateChannelOwner
      service <- mkService(Some(expected))
      result <- service.process(output)
    } yield expect.same(Left(NonEmptyList.of(expected)), result)

  }

  def mkService(failed: Option[StateChannelValidator.StateChannelValidationError] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.fold[StateChannelValidator.StateChannelValidationErrorOr[StateChannelOutput]](output.validNec)(_.invalidNec))
    }

    for {
      dagQueue <- Queue.unbounded[IO, Signed[SHIBlock]]
      scQueue <- Queue.unbounded[IO, StateChannelOutput]
    } yield StateChannelService.make[IO](L0Cell.mkL0Cell[IO](dagQueue, scQueue), validator)
  }

  def mkStateChannelOutput()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncKryo(binary, keyPair)

  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
