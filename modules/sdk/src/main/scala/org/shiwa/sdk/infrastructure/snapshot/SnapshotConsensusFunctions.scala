package org.shiwa.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.{Applicative, Eq, Order}

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.shiwa.ext.cats.syntax.next._
import org.shiwa.schema._
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.block.processing.{BlockAcceptanceResult, deprecationThreshold}
import org.shiwa.sdk.domain.consensus.ConsensusFunctions
import org.shiwa.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.shiwa.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.Signed
import org.shiwa.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
case object NoTipsRemaining extends NoStackTrace
case object ArtifactMismatch extends InvalidArtifact

abstract class SnapshotConsensusFunctions[
  F[_]: Async: SecurityProvider,
  T <: Transaction,
  B <: Block[T]: Order,
  P <: StateProof,
  Event,
  Artifact <: Snapshot[T, B]: Eq,
  Context <: SnapshotInfo[P],
  Trigger <: ConsensusTrigger
](implicit ordering: Ordering[BlockAsActiveTip[B]])
    extends ConsensusFunctions[F, Event, SnapshotOrdinal, Artifact, Context] {

  def getRequiredCollateral: Amount

  def triggerPredicate(event: Event): Boolean = true

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: peer.PeerId): F[Boolean] =
    peerId.toAddress[F].map { address =>
      lastContext.balances.getOrElse(address, Balance.empty).satisfiesCollateral(getRequiredCollateral)
    }

  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact
  ): F[Either[InvalidArtifact, (Artifact, Context)]] = {
    val events = artifact.blocks.unsorted.map(_.block.asInstanceOf[Event])

    createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events).map {
      case (recreatedArtifact, context, _) =>
        if (recreatedArtifact === artifact)
          (artifact, context).asRight[InvalidArtifact]
        else
          ArtifactMismatch.asLeft[(Artifact, Context)]
    }
  }

  protected def getUpdatedTips(
    lastActive: SortedSet[ActiveTip],
    lastDeprecated: SortedSet[DeprecatedTip],
    acceptanceResult: BlockAcceptanceResult[B],
    currentOrdinal: SnapshotOrdinal
  ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[BlockAsActiveTip[B]]) = {
    val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
    val accepted =
      acceptanceResult.accepted.map { case (block, usages) => BlockAsActiveTip(block, usages) }.toSortedSet
    val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
      val maybeUpdatedUsage = usagesUpdate.get(at.block)
      Either.cond(
        maybeUpdatedUsage.exists(_ >= deprecationThreshold),
        DeprecatedTip(at.block, currentOrdinal),
        maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
      )
    }.bimap(_.toSortedSet, _.toSortedSet)
    val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
    val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

    (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
  }

  protected def getTipsUsages(
    lastActive: Set[ActiveTip],
    lastDeprecated: Set[DeprecatedTip]
  ): Map[BlockReference, NonNegLong] = {
    val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
    val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

    activeTipsUsages ++ deprecatedTipsUsages
  }

  protected def getHeightAndSubHeight(
    lastGS: Artifact,
    deprecated: Set[DeprecatedTip],
    remainedActive: Set[ActiveTip],
    accepted: Set[BlockAsActiveTip[B]]
  ): F[(Height, SubHeight)] = {
    val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
      .map(_.block.height)).toList

    for {
      height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

      _ <-
        if (height < lastGS.height)
          InvalidHeight(lastGS.height, height).raiseError
        else
          Applicative[F].unit

      subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
    } yield (height, subHeight)
  }

}