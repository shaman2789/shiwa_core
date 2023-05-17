package org.shiwa.infrastructure.snapshot

import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.shiwa.schema.block.SHIBlock
import org.shiwa.sdk.domain.Daemon
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.infrastructure.snapshot.daemon.SnapshotEventsPublisherDaemon
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelOutput

import fs2.Stream
import io.circe.disjunctionCodecs._

object GlobalSnapshotEventsPublisherDaemon {

  def make[F[_]: Async: Supervisor](
    stateChannelOutputs: Queue[F, StateChannelOutput],
    l1OutputQueue: Queue[F, Signed[SHIBlock]],
    gossip: Gossip[F]
  ): Daemon[F] = {
    val events: Stream[F, GlobalSnapshotEvent] = Stream
      .fromQueueUnterminated(stateChannelOutputs)
      .map(_.asLeft[SHIEvent])
      .merge(
        Stream
          .fromQueueUnterminated(l1OutputQueue)
          .map(_.asRight[StateChannelEvent])
      )

    SnapshotEventsPublisherDaemon
      .make(
        gossip,
        events
      )
      .spawn
  }

}
