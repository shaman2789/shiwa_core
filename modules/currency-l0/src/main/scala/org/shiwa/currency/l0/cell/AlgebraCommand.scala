package org.shiwa.currency.l0.cell

import org.shiwa.currency.schema.currency.CurrencyBlock
import org.shiwa.kernel.Ω
import org.shiwa.security.signature.Signed

sealed trait AlgebraCommand extends Ω

object AlgebraCommand {
  case class EnqueueL1BlockData(data: Signed[CurrencyBlock]) extends AlgebraCommand
  case object NoAction extends AlgebraCommand
}
