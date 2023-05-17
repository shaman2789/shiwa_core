package org.shiwa.sdk.domain.block.processing

import org.shiwa.schema.Block
import org.shiwa.security.signature.Signed

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong

@derive(show)
case class BlockAcceptanceResult[B <: Block[_]](
  contextUpdate: BlockAcceptanceContextUpdate,
  accepted: List[(Signed[B], NonNegLong)],
  notAccepted: List[(Signed[B], BlockNotAcceptedReason)]
)
