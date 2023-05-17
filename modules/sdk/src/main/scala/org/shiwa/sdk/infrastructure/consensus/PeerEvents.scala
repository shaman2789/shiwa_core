package org.shiwa.sdk.infrastructure.consensus

import cats.syntax.option._

import org.shiwa.schema.gossip.Ordinal

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class PeerEvents[Event](
  events: List[(Ordinal, Event)],
  trigger: Option[Ordinal]
)

object PeerEvents {

  def empty[Event]: PeerEvents[Event] =
    PeerEvents(List.empty, none)

}
