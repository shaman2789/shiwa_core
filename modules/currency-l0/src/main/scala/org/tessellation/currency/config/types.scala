package org.tessellation.currency.config

import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types._

object types {
  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    gossip: GossipConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig
  )
}
