package org.shiwa.ext.http4s

import org.shiwa.schema.address.{Address, SHIAddressRefined}

import eu.timepit.refined.refineV

object AddressVar {
  def unapply(str: String): Option[Address] = refineV[SHIAddressRefined](str).toOption.map(Address(_))
}
