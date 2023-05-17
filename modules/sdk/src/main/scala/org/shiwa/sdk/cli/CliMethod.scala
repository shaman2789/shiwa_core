package org.shiwa.sdk.cli

import cats.syntax.eq._

import scala.concurrent.duration._

import org.shiwa.cli.env._
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.node.NodeState
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.AppEnvironment.Mainnet
import org.shiwa.sdk.config.types._

import eu.timepit.refined.auto._
import fs2.io.file.Path

trait CliMethod {

  val keyStore: StorePath
  val alias: KeyAlias
  val password: Password

  val environment: AppEnvironment

  val seedlistPath: Option[SeedListPath]

  val trustRatingsPath: Option[Path]

  val httpConfig: HttpConfig

  val stateAfterJoining: NodeState

  val collateralAmount: Option[Amount]

  val stateChannelSeedlistConfig: StateChannelSeedlistConfig

  val collateralConfig = (environment: AppEnvironment, amount: Option[Amount]) =>
    CollateralConfig(
      amount = amount
        .filter(_ => environment =!= Mainnet)
        .getOrElse(Amount(250_000_00000000L))
    )

  val gossipConfig: GossipConfig = GossipConfig(
    storage = RumorStorageConfig(
      peerRumorsCapacity = 50L,
      activeCommonRumorsCapacity = 20L,
      seenCommonRumorsCapacity = 50L
    ),
    daemon = GossipDaemonConfig(
      peerRound = GossipRoundConfig(
        fanout = 1,
        interval = 0.2.seconds,
        maxConcurrentRounds = 4
      ),
      commonRound = GossipRoundConfig(
        fanout = 1,
        interval = 0.5.seconds,
        maxConcurrentRounds = 2
      )
    )
  )

  val leavingDelay = 30.seconds

  def healthCheckConfig(pingEnabled: Boolean) = HealthCheckConfig(
    removeUnresponsiveParallelPeersAfter = 10.seconds,
    requestProposalsAfter = 8.seconds,
    ping = PingHealthCheckConfig(
      enabled = pingEnabled,
      concurrentChecks = 3,
      defaultCheckTimeout = 6.seconds,
      defaultCheckAttempts = 3,
      ensureCheckInterval = 10.seconds
    )
  )

  lazy val sdkConfig: SdkConfig = SdkConfig(
    environment,
    gossipConfig,
    httpConfig,
    leavingDelay,
    stateAfterJoining,
    collateralConfig(environment, collateralAmount),
    stateChannelSeedlistConfig
  )

}