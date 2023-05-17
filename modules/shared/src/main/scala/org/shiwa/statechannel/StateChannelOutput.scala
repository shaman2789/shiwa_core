package org.shiwa.statechannel

import org.shiwa.schema.address.Address
import org.shiwa.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
case class StateChannelOutput(
  address: Address,
  snapshotBinary: Signed[StateChannelSnapshotBinary]
)
