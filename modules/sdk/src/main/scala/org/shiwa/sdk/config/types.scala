package org.shiwa.sdk.config

import scala.concurrent.duration.FiniteDuration

import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Amount
import org.shiwa.schema.node.NodeState

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.io.file.Path

object types {

  case class SdkConfig(
    environment: AppEnvironment,
    gossipConfig: GossipConfig,
    httpConfig: HttpConfig,
    leavingDelay: FiniteDuration,
    stateAfterJoining: NodeState,
    collateral: CollateralConfig,
    stateChannelSeedlist: StateChannelSeedlistConfig
  )

  case class RumorStorageConfig(
    peerRumorsCapacity: PosLong,
    activeCommonRumorsCapacity: NonNegLong,
    seenCommonRumorsCapacity: NonNegLong
  )

  case class GossipDaemonConfig(
    peerRound: GossipRoundConfig,
    commonRound: GossipRoundConfig
  )

  case class GossipRoundConfig(
    fanout: PosInt,
    interval: FiniteDuration,
    maxConcurrentRounds: PosInt
  )

  case class GossipConfig(
    storage: RumorStorageConfig,
    daemon: GossipDaemonConfig
  )

  case class ConsensusConfig(
    timeTriggerInterval: FiniteDuration,
    declarationTimeout: FiniteDuration,
    declarationRangeLimit: NonNegLong,
    lockDuration: FiniteDuration
  )

  case class SnapshotConfig(
    consensus: ConsensusConfig,
    snapshotPath: Path,
    incrementalTmpSnapshotPath: Path,
    incrementalPersistedSnapshotPath: Path,
    inMemoryCapacity: NonNegLong
  )

  case class HttpClientConfig(
    timeout: FiniteDuration,
    idleTimeInPool: FiniteDuration
  )

  case class HttpServerConfig(
    host: Host,
    port: Port,
    shutdownTimeout: FiniteDuration
  )

  case class HttpConfig(
    externalIp: Host,
    client: HttpClientConfig,
    publicHttp: HttpServerConfig,
    p2pHttp: HttpServerConfig,
    cliHttp: HttpServerConfig
  )

  case class HealthCheckConfig(
    ping: PingHealthCheckConfig,
    removeUnresponsiveParallelPeersAfter: FiniteDuration,
    requestProposalsAfter: FiniteDuration
  )

  case class PingHealthCheckConfig(
    enabled: Boolean,
    concurrentChecks: PosInt,
    defaultCheckTimeout: FiniteDuration,
    defaultCheckAttempts: PosInt,
    ensureCheckInterval: FiniteDuration
  )

  case class CollateralConfig(
    amount: Amount
  )

  case class StateChannelSeedlistConfig(
    seedlist: Option[Set[Address]]
  )
}