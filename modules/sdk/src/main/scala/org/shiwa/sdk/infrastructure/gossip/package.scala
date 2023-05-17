package org.shiwa.sdk.infrastructure

import cats.data.{Kleisli, OptionT}

import org.shiwa.schema.gossip.RumorRaw
import org.shiwa.schema.peer.PeerId

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (RumorRaw, PeerId), Unit]

  val rumorLoggerName = "RumorLogger"

}
