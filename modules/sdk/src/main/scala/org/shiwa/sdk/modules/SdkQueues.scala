package org.shiwa.sdk.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.functor._

import org.shiwa.schema.gossip.RumorRaw
import org.shiwa.security.Hashed

object SdkQueues {

  def make[F[_]: Concurrent]: F[SdkQueues[F]] =
    for {
      rumorQueue <- Queue.unbounded[F, Hashed[RumorRaw]]
    } yield
      new SdkQueues[F] {
        val rumor = rumorQueue
      }
}

sealed abstract class SdkQueues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
}
