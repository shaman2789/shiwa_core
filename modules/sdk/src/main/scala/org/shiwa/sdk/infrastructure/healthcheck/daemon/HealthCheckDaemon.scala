package org.shiwa.sdk.infrastructure.healthcheck.daemon

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.functor._

import scala.concurrent.duration._

import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.domain.healthcheck.HealthChecks

import fs2._

trait HealthCheckDaemon[F[_]] extends Daemon[F] {}

object HealthCheckDaemon {

  def make[F[_]: Async](healthChecks: HealthChecks[F])(implicit S: Supervisor[F]): HealthCheckDaemon[F] = new HealthCheckDaemon[F] {

    def start: F[Unit] =
      S.supervise(periodic).void

    private def periodic: F[Unit] =
      Stream
        .awakeEvery(10.seconds)
        .evalTap { _ =>
          healthChecks.trigger()
        }
        .compile
        .drain

  }

}