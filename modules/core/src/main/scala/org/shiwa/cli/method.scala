package org.shiwa.cli

import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._

import scala.concurrent.duration._

import org.shiwa.cli.env._
import org.shiwa.cli.http
import org.shiwa.cli.incremental._
import org.shiwa.config.types._
import org.shiwa.ext.decline.WithOpts
import org.shiwa.ext.decline.decline._
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.node.NodeState
import org.shiwa.sdk.cli._
import org.shiwa.sdk.cli.opts.{genesisPathOpts, trustRatingsPathOpts}
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types._
import org.shiwa.security.hash.Hash

import com.monovore.decline.Opts
import com.monovore.decline.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Path

object method {

  sealed trait Run extends CliMethod {
    val dbConfig: DBConfig

    val snapshotConfig: SnapshotConfig

    val appConfig: AppConfig = AppConfig(
      environment = environment,
      http = httpConfig,
      db = dbConfig,
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
            maxConcurrentRounds = 8
          ),
          commonRound = GossipRoundConfig(
            fanout = 1,
            interval = 0.5.seconds,
            maxConcurrentRounds = 4
          )
        )
      ),
      trust = TrustConfig(
        TrustDaemonConfig(
          10.minutes
        )
      ),
      healthCheck = healthCheckConfig(false),
      snapshot = snapshotConfig,
      collateral = collateralConfig(environment, collateralAmount),
      rewards = RewardsConfig()
    )

    val stateAfterJoining: NodeState = NodeState.WaitingForDownload

    val stateChannelSeedlistConfig: StateChannelSeedlistConfig =
      StateChannelSeedlistConfig(seedlist = StateChannelSeedlist.get(environment))

    val lastFullGlobalSnapshotOrdinal: SnapshotOrdinal

  }

  case class RunGenesis(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    genesisPath: Path,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    startingEpochProgress: EpochProgress,
    trustRatingsPath: Option[Path]
  ) extends Run {

    val lastFullGlobalSnapshotOrdinal = SnapshotOrdinal.MinValue
  }

  object RunGenesis extends WithOpts[RunGenesis] {

    val startingEpochProgressOpts: Opts[EpochProgress] = Opts
      .option[NonNegLong]("startingEpochProgress", "Set starting progress for rewarding at the specific epoch")
      .map(EpochProgress(_))
      .withDefault(EpochProgress.MinValue)

    val opts: Opts[RunGenesis] = Opts.subcommand("run-genesis", "Run genesis mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        genesisPathOpts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        startingEpochProgressOpts,
        trustRatingsPathOpts
      ).mapN(RunGenesis.apply)
    }
  }

  case class RunRollback(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    rollbackHash: Hash,
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    trustRatingsPath: Option[Path]
  ) extends Run

  object RunRollback extends WithOpts[RunRollback] {

    val rollbackHashOpts: Opts[Hash] = Opts.argument[Hash]("rollbackHash")

    val opts: Opts[RunRollback] = Opts.subcommand("run-rollback", "Run rollback mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        rollbackHashOpts,
        lastFullGlobalSnapshotOrdinalOpts,
        trustRatingsPathOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              db,
              http,
              environment,
              snapshot,
              seedlistPath,
              collateralAmount,
              rollbackHash,
              lastGlobalSnapshot,
              trustRatingsPath
            ) =>
          val lastGS =
            (if (environment === AppEnvironment.Dev) lastGlobalSnapshot else lastFullGlobalSnapshot.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunRollback(
            storePath,
            keyAlias,
            password,
            db,
            http,
            environment,
            snapshot,
            seedlistPath,
            collateralAmount,
            rollbackHash,
            lastGS,
            trustRatingsPath
          )
      }
    }
  }

  case class RunValidator(
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password,
    dbConfig: DBConfig,
    httpConfig: HttpConfig,
    environment: AppEnvironment,
    snapshotConfig: SnapshotConfig,
    seedlistPath: Option[SeedListPath],
    collateralAmount: Option[Amount],
    trustRatingsPath: Option[Path],
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal
  ) extends Run

  object RunValidator extends WithOpts[RunValidator] {

    val opts: Opts[RunValidator] = Opts.subcommand("run-validator", "Run validator mode") {
      (
        StorePath.opts,
        KeyAlias.opts,
        Password.opts,
        db.opts,
        http.opts,
        AppEnvironment.opts,
        snapshot.opts,
        SeedListPath.opts,
        CollateralAmountOpts.opts,
        trustRatingsPathOpts,
        lastFullGlobalSnapshotOrdinalOpts
      ).mapN {
        case (
              storePath,
              keyAlias,
              password,
              db,
              http,
              environment,
              snapshot,
              seedlistPath,
              collateralAmount,
              trustRatingsPath,
              lastGlobalSnapshot
            ) =>
          val lastGS =
            (if (environment === AppEnvironment.Dev) lastGlobalSnapshot else lastFullGlobalSnapshot.get(environment))
              .getOrElse(SnapshotOrdinal.MinValue)

          RunValidator(
            storePath,
            keyAlias,
            password,
            db,
            http,
            environment,
            snapshot,
            seedlistPath,
            collateralAmount,
            trustRatingsPath,
            lastGS
          )
      }
    }
  }

  val opts: Opts[Run] =
    RunGenesis.opts.orElse(RunValidator.opts).orElse(RunRollback.opts)
}