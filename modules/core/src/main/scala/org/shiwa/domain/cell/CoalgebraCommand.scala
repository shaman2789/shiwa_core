package org.shiwa.domain.cell

import org.shiwa.schema.block.SHIBlock
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelOutput

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessSHIL1(data: Signed[SHIBlock]) extends CoalgebraCommand
  case class ProcessStateChannelSnapshot(snapshot: StateChannelOutput) extends CoalgebraCommand
}
