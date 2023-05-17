package org.shiwa.dag.l1.infrastructure.block.rumor

import cats.effect.Async
import cats.effect.std.Queue

import scala.reflect.runtime.universe.TypeTag

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.gossip.CommonRumor
import org.shiwa.sdk.infrastructure.gossip.RumorHandler
import org.shiwa.security.signature.Signed

import io.circe.Decoder

object handler {

  def blockRumorHandler[F[_]: Async: KryoSerializer, B <: Block[_]: Decoder: TypeTag](
    peerBlockQueue: Queue[F, Signed[B]]
  ): RumorHandler[F] =
    RumorHandler.fromCommonRumorConsumer[F, Signed[B]] {
      case CommonRumor(signedBlock) =>
        peerBlockQueue.offer(signedBlock)
    }
}
