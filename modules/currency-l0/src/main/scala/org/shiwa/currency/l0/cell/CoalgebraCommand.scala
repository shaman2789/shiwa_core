package org.shiwa.currency.l0.cell

import org.shiwa.currency.schema.currency.CurrencyBlock
import org.shiwa.security.signature.Signed

sealed trait CoalgebraCommand

object CoalgebraCommand {
  case class ProcessL1Block(data: Signed[CurrencyBlock]) extends CoalgebraCommand
}
