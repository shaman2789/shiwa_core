package org.shiwa.currency.l0.config

import org.shiwa.schema.peer.L0Peer
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types._

object types {
  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    gossip: GossipConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig,
    globalL0Peer: L0Peer
  )
}
