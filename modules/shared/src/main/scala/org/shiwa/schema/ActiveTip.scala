package org.shiwa.schema

import org.shiwa.ext.cats.data.OrderBasedOrdering

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong

@derive(order, show, encoder, decoder)
case class ActiveTip(
  block: BlockReference,
  usageCount: NonNegLong,
  introducedAt: SnapshotOrdinal
)

object ActiveTip {
  implicit object OrderingInstance extends OrderBasedOrdering[ActiveTip]
}
