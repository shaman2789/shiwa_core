package org.shiwa.sdk.infrastructure.snapshot.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.eq._
import cats.syntax.functor._

import org.shiwa.schema.node.NodeState
import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.snapshot.programs.Download

trait DownloadDaemon[F[_]] extends Daemon[F] {}

object DownloadDaemon {

  def make[F[_]: Async](nodeStorage: NodeStorage[F], download: Download[F])(implicit S: Supervisor[F]) = new DownloadDaemon[F] {
    def start: F[Unit] = S.supervise(watchForDownload).void

    private def watchForDownload: F[Unit] =
      nodeStorage.nodeStates
        .filter(_ === NodeState.WaitingForDownload)
        .evalTap { _ =>
          download.download
        }
        .compile
        .drain
  }
}
