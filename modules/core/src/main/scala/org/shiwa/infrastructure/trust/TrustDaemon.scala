package org.shiwa.infrastructure.trust

import cats.effect.std.Supervisor
import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.config.types.TrustDaemonConfig
import org.shiwa.domain.trust.storage.TrustStorage
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.trust.TrustInfo
import org.shiwa.sdk.domain.Daemon

trait TrustDaemon[F[_]] extends Daemon[F]

object TrustDaemon {

  def make[F[_]: Async](
    cfg: TrustDaemonConfig,
    trustStorage: TrustStorage[F],
    selfPeerId: PeerId
  )(implicit S: Supervisor[F]): TrustDaemon[F] = new TrustDaemon[F] {

    def start: F[Unit] =
      for {
        _ <- S.supervise(modelUpdate.foreverM).void
      } yield ()

    private def calculatePredictedTrust(trust: Map[PeerId, TrustInfo]): Map[PeerId, Double] =
      TrustModel.calculateTrust(trust, selfPeerId)

    private def modelUpdate: F[Unit] =
      for {
        _ <- Temporal[F].sleep(cfg.interval)
        predictedTrust <- trustStorage.getTrust.map(calculatePredictedTrust)
        _ <- trustStorage.updatePredictedTrust(predictedTrust)
      } yield ()

  }
}