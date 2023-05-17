package org.shiwa.merkletree

import cats.data.{NonEmptyList, NonEmptySet, Validated}
import cats.effect.{Async, IO, Resource}
import cats.syntax.flatMap._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.peer.PeerId
import org.shiwa.security.Hashed
import org.shiwa.security.hash.Hash
import org.shiwa.security.hex.Hex
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.signature.{Signature, SignatureProof}
import org.shiwa.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object MerkleTreeValidatorSuite extends MutableIOSuite {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("should succeed when state matches snapshot's state proof") { implicit ks =>
    val globalSnapshotInfo = GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap(Address("SHI2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> Balance(100L)),
      SortedMap.empty,
      SortedMap.empty
    )
    for {
      snapshot <- globalIncrementalSnapshot(globalSnapshotInfo)
      result <- StateProofValidator.validate(snapshot, globalSnapshotInfo)
    } yield expect.same(Validated.Valid(()), result)
  }

  test("should fail when state doesn't match snapshot's state proof for") { implicit ks =>
    val globalSnapshotInfo = GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap(Address("SHI2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> Balance(100L)),
      SortedMap.empty,
      SortedMap.empty
    )

    for {
      snapshot <- globalIncrementalSnapshot(globalSnapshotInfo)
      result <- StateProofValidator.validate(snapshot, GlobalSnapshotInfo.empty)
    } yield expect.same(Validated.Invalid(StateProofValidator.StateBroken(SnapshotOrdinal(NonNegLong(1L)), snapshot.hash)), result)
  }

  private def globalIncrementalSnapshot[F[_]: Async: KryoSerializer](
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Hashed[GlobalIncrementalSnapshot]] =
    globalSnapshotInfo.stateProof[F].flatMap { sp =>
      Signed(
        GlobalIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(1L)),
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          EpochProgress.MinValue,
          NonEmptyList.of(PeerId(Hex(""))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = sp
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      ).toHashed[F]
    }

}
