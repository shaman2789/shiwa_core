package org.shiwa.dag.l1.config

import org.shiwa.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.types.string.NonEmptyString

object types {

  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    gossip: GossipConfig,
    consensus: ConsensusConfig,
    healthCheck: HealthCheckConfig,
    collateral: CollateralConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )
}
