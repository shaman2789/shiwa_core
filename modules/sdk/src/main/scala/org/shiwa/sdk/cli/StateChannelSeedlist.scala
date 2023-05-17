package org.shiwa.sdk.cli

import org.shiwa.schema.address.Address
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.AppEnvironment._

object StateChannelSeedlist {

  def get(env: AppEnvironment): Option[Set[Address]] =
    env match {
      case Dev     => None
      case Testnet => Some(Set.empty)
      case Mainnet => Some(Set.empty)
    }
}
