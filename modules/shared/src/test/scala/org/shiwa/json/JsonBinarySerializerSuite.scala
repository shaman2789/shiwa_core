package org.shiwa.json

import cats.MonadThrow
import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.security.hash.{Hash, ProofsHash}
import org.shiwa.security.hex.Hex
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.signature.{Signature, SignatureProof}
import org.shiwa.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object JsonBinarySerializerSuite extends MutableIOSuite {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("should deserialize properly serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty)).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)
      expect.same(Right(signedSnapshot), deserialized)
    }
  }

  test("should not deserialize different serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty)).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[CurrencySnapshot](serialized)
      expect.same(true, deserialized.isLeft)
    }
  }

  private def currencyIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](
    hash: Hash,
    currencySnapshotInfo: CurrencySnapshotInfo
  ): F[Signed[CurrencyIncrementalSnapshot]] =
    currencySnapshotInfo.stateProof[F].map { sp =>
      Signed(
        CurrencyIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(56L)),
          Height(123L),
          SubHeight(1L),
          hash,
          SortedSet.empty,
          SortedSet.empty,
          SnapshotTips(
            SortedSet(
              DeprecatedTip(BlockReference(Height(122L), ProofsHash("aaaa")), SnapshotOrdinal(55L)),
              DeprecatedTip(BlockReference(Height(122L), ProofsHash("cccc")), SnapshotOrdinal(55L))
            ),
            SortedSet(ActiveTip(BlockReference(Height(122L), ProofsHash("bbbb")), 2L, SnapshotOrdinal(55L)))
          ),
          stateProof = sp
        ),
        NonEmptySet.one(SignatureProof(ID.Id(Hex("")), Signature(Hex(""))))
      )
    }
}