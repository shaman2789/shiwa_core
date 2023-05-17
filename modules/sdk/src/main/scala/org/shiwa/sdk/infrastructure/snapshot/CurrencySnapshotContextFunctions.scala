package org.shiwa.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.shiwa.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.sdk.domain.block.processing._
import org.shiwa.sdk.domain.snapshot.SnapshotContextFunctions
import org.shiwa.sdk.infrastructure.snapshot.GlobalSnapshotContextFunctions.CannotApplyRewardsError
import org.shiwa.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

abstract class CurrencySnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]

object CurrencySnapshotContextFunctions {
  def make[F[_]: Async: KryoSerializer](snapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F]) =
    new CurrencySnapshotContextFunctions[F] {
      def createContext(
        context: CurrencySnapshotInfo,
        lastArtifact: CurrencyIncrementalSnapshot,
        signedArtifact: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = for {
        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        blocksForAcceptance = signedArtifact.blocks.toList.map(_.block)

        (acceptanceResult, acceptedRewardTxs, snapshotInfo, _) <- snapshotAcceptanceManager.accept(
          blocksForAcceptance,
          context,
          lastActiveTips,
          lastDeprecatedTips,
          _ => signedArtifact.rewards.pure[F]
        )
        _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map { case (_, reason) => reason })
          .raiseError[F, Unit]
          .whenA(acceptanceResult.notAccepted.nonEmpty)
        diffRewards = acceptedRewardTxs -- signedArtifact.rewards
        _ <- CannotApplyRewardsError(diffRewards).raiseError[F, Unit].whenA(diffRewards.nonEmpty)

      } yield snapshotInfo
    }

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build currency snapshot ${reasons.show}"
  }
}