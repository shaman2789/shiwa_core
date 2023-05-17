package org.shiwa.modules

import cats.effect.Concurrent
import cats.effect.std.Queue
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.gossip.RumorRaw
import org.shiwa.sdk.modules.SdkQueues
import org.shiwa.security.Hashed
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelOutput

object Queues {

  def make[F[_]: Concurrent](sdkQueues: SdkQueues[F]): F[Queues[F]] =
    for {
      stateChannelOutputQueue <- Queue.unbounded[F, StateChannelOutput]
      l1OutputQueue <- Queue.unbounded[F, Signed[SHIBlock]]
    } yield
      new Queues[F] {
        val rumor = sdkQueues.rumor
        val stateChannelOutput = stateChannelOutputQueue
        val l1Output = l1OutputQueue
      }
}

sealed abstract class Queues[F[_]] private {
  val rumor: Queue[F, Hashed[RumorRaw]]
  val stateChannelOutput: Queue[F, StateChannelOutput]
  val l1Output: Queue[F, Signed[SHIBlock]]
}
