package org.shiwa.sdk.domain.healthcheck.consensus

import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.healthcheck.consensus.types._

trait HealthCheckConsensusDriver[K <: HealthCheckKey, A <: HealthCheckStatus, B <: ConsensusHealthStatus[
  K,
  A
], C <: HealthCheckConsensusDecision] {

  def removePeersWithParallelRound: Boolean

  def calculateConsensusOutcome(
    key: K,
    ownStatus: A,
    selfId: PeerId,
    receivedStatuses: List[B]
  ): C

  def consensusHealthStatus(
    key: K,
    ownStatus: A,
    roundIds: Set[HealthCheckRoundId],
    selfId: PeerId,
    clusterState: Set[PeerId]
  ): B
}
