package org.shiwa.domain.cell

import org.shiwa.kernel.Ω
import org.shiwa.schema.block.SHIBlock
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelOutput

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueStateChannelSnapshot(snapshot: StateChannelOutput) extends AlgebraCommand
  case class EnqueueSHIL1Data(data: Signed[SHIBlock]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
