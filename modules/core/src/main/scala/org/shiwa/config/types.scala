package org.shiwa.config

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration

import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.config.types._

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric._
import eu.timepit.refined.types.string.NonEmptyString
import io.estatico.newtype.macros.newtype

object types {
  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    db: DBConfig,
    gossip: GossipConfig,
    trust: TrustConfig,
    healthCheck: HealthCheckConfig,
    snapshot: SnapshotConfig,
    collateral: CollateralConfig,
    rewards: RewardsConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )

  case class TrustDaemonConfig(
    interval: FiniteDuration
  )

  case class TrustConfig(
    daemon: TrustDaemonConfig
  )

  @newtype
  case class Weight(value: NonNegLong)

  case class ProgramsDistributionConfig(
    weights: Map[Address, Weight] = Map(
      Address("SHISTARDUSTCOLLECTIVEHZOIPHXZUBFGNXWJETZVSPAPAHMLXS") -> Weight(5L), // stardust tax primary
      Address("SHI8VT7bxjs1XXBAzJGYJDaeyNxuThikHeUTp9XY") -> Weight(5L), // stardust tax secondary
      Address("SHI77VVVRvdZiYxZ2hCtkHz68h85ApT5b2xzdTkn") -> Weight(20L), // soft staking
      Address("SHI0qE5tkz6cMUD5M2dkqgfV4TQCzUUdAP5MFM9P") -> Weight(1L), // testnet
      Address("SHI3RXBWBJq1Bf38rawASakLHKYMbRhsDckaGvGu") -> Weight(65L) // data pool
    ),
    remainingWeight: Weight = Weight(4L) // facilitators
  )

  case class RewardsConfig(
    programs: ProgramsDistributionConfig = ProgramsDistributionConfig(),
    rewardsPerEpoch: SortedMap[EpochProgress, Amount] = SortedMap(
      EpochProgress(1296000L) -> Amount(658_43621389L),
      EpochProgress(2592000L) -> Amount(329_21810694L),
      EpochProgress(3888000L) -> Amount(164_60905347L),
      EpochProgress(5184000L) -> Amount(82_30452674L)
    )
  )
}
