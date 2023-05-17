package org.shiwa.statechannel

import cats.Show
import cats.syntax.show._

import org.shiwa.currency.schema.currency.SnapshotFee
import org.shiwa.schema.arrayOrder
import org.shiwa.security.hash.Hash

import derevo.cats.{eqv, order}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, order, eqv)
case class StateChannelSnapshotBinary(
  lastSnapshotHash: Hash,
  content: Array[Byte],
  fee: SnapshotFee
)

object StateChannelSnapshotBinary {
  implicit val show: Show[StateChannelSnapshotBinary] = s =>
    s"StateChannelSnapshotBinary(lastSnapshotHash=${s.lastSnapshotHash.show}, fee=${s.fee.show})"
}
