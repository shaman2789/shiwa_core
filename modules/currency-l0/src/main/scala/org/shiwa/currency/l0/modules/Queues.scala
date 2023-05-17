package org.shiwa.currency.l0.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.shiwa.currency.schema.currency.CurrencyBlock
import org.shiwa.schema.gossip.RumorRaw
import org.shiwa.sdk.modules.SdkQueues
import org.shiwa.security.Hashed
import org.shiwa.security.signature.Signed

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      l1OutputQueue <- Queue.unbounded[F, Signed[CurrencyBlock]]
    } yield
      new Queues[F] {
        val rumor: Queue[F, Hashed[RumorRaw]] = sdkQueues.rumor
        val l1Output: Queue[F, Signed[CurrencyBlock]] = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val l1Output: Queue[F, Signed[CurrencyBlock]]
}
