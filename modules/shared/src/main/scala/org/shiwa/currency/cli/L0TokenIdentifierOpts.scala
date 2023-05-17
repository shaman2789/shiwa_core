package org.shiwa.currency.cli

import org.shiwa.schema.address.{Address, SHIAddress}

import com.monovore.decline.Opts
import com.monovore.decline.refined.refTypeArgument

object L0TokenIdentifierOpts {

  val opts: Opts[Address] = Opts
    .option[SHIAddress]("l0-token-identifier", help = "L0 token identifier address")
    .orElse(Opts.env[SHIAddress]("CL_L0_TOKEN_IDENTIFIER", help = "L0 token identifier address"))
    .map(Address(_))
}
