package org.shiwa.block

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.SortedSet

import org.shiwa.schema.BlockReference
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.generators.{signedOf, signedTransactionGen}
import org.shiwa.security.signature.Signed

import org.scalacheck.{Arbitrary, Gen}

object generators {

  val blockReferencesGen: Gen[NonEmptyList[BlockReference]] =
    Gen.nonEmptyListOf(Arbitrary.arbitrary[BlockReference]).map(NonEmptyList.fromListUnsafe(_))

  val dagBlockGen: Gen[SHIBlock] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedTransactionGen
    } yield SHIBlock(blockReferences, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val signedSHIBlockGen: Gen[Signed[SHIBlock]] = signedOf(dagBlockGen)
  implicit val signedSHIBlockArbitrary = Arbitrary(signedSHIBlockGen)

}
