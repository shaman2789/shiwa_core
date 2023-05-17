package org.shiwa.dag.l1.infrastructure.trust.storage

import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.trust._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.scalacheck.numeric._
import org.scalacheck.Arbitrary.arbitrary

object TrustDbValuesGenerator {

  val arbitraryTrustValue =
    arbitrary[Double Refined TrustValueRefinement]

  val genScore = arbitraryTrustValue.map(Score(_))

  val genRating = arbitraryTrustValue.map(Rating(_))

  val genObservationAdjustment = arbitraryTrustValue.map(ObservationAdjustment(_))

  val genTrustDbValues = for {
    peerId <- arbitrary[PeerId]
    score <- genScore
    rating <- genRating
    observationAdjustment <- genObservationAdjustment
  } yield
    TrustDbValues(
      peerId,
      Some(score),
      Some(rating),
      Some(observationAdjustment)
    )
}
