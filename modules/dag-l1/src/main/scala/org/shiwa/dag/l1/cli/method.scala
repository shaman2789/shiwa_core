package org.shiwa.dag.l1.cli

import cats.syntax.contravariantSemigroupal._

import scala.concurrent.duration.{DurationDouble, DurationInt}

import org.shiwa.cli.env._
import org.shiwa.dag.l1.config.types.AppConfig
import org.shiwa.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.peer.L0Peer
import org.shiwa.sdk.cli.opts.trustRatingsPathOpts
import org.shiwa.sdk.cli.{CliMethod, CollateralAmountOpts, L0PeerOpts}
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types._

import com.monovore.decline.Opts
import eu.timepit.refined.auto.autoRefineV
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val l0Peer: L0Peer

    val stateAfterJoining: NodeState = NodeState.Ready

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
      gossip = GossipConfig(
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
      ),
      consensus = ConsensusConfig(
        peersCount = 2,
        tipsCount = 2,
        timeout = 45.seconds,
        pullTxsCount = 100L
      ),
      healthCheck = healthCheckConfig(false),
      collateral = collateralConfig(environment, collateralAmount)
    )

    val stateChannelSeedlistConfig: StateChannelSeedlistConfig = StateChannelSeedlistConfig(None)
  }

  case class RunInitialValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunInitialValidator {

    val opts = Opts.subcommand("run-initial-validator", "Run initial validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunInitialValidator.apply)
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    environment: AppEnvironment,
    httpConfig: HttpConfig,
    l0Peer: L0Peer,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunValidator {

    val opts = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        AppEnvironment.opts,
        http.opts,
        L0PeerOpts.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts
      ).mapN(RunValidator.apply)
    }
  }

  val opts: Opts[Run] =
    RunInitialValidator.opts.orElse(RunValidator.opts)
}