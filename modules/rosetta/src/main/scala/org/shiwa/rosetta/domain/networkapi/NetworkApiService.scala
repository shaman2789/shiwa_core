package org.shiwa.rosetta.domain.networkapi

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.functor._

import org.shiwa.BuildInfo
import org.shiwa.rosetta.domain.NetworkIdentifier
import org.shiwa.rosetta.domain.error.{LatestSnapshotNotFound, NetworkApiError}
import org.shiwa.rosetta.domain.networkapi.model.options._
import org.shiwa.rosetta.domain.networkapi.model.status._
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.timestamp.SnapshotTimestamp
import org.shiwa.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.security.Hashed
import org.shiwa.security.hash.Hash
import org.shiwa.security.hex.Hex

trait NetworkApiService[F[_]] {
  def list(appEnv: AppEnvironment): List[NetworkIdentifier]
  def options: NetworkApiOptions
  def status: EitherT[F, NetworkApiError, NetworkStatusResponse]
}

object NetworkApiService {
  private val rosettaVersion = "1.4.14"
  private val version = Version(rosettaVersion = rosettaVersion, nodeVersion = BuildInfo.version)

  type LastSnapshotInfo = (Hashed[GlobalIncrementalSnapshot], SnapshotTimestamp)

  def make[F[_]: Async](
    lastSnapshot: F[Option[LastSnapshotInfo]],
    genesisOrdinalAndHash: F[(SnapshotOrdinal, Hash)],
    nodeState: F[NodeState]
  ): NetworkApiService[F] = new NetworkApiService[F] {
    def list(appEnv: AppEnvironment): List[NetworkIdentifier] =
      NetworkIdentifier.fromAppEnvironment(appEnv).toList

    def options: NetworkApiOptions =
      NetworkApiOptions(version, Allow.default)

    def status: EitherT[F, NetworkApiError, NetworkStatusResponse] = for {
      (lastSnapshot, lastSnapshotTimestamp) <- EitherT.fromOptionF[F, NetworkApiError, LastSnapshotInfo](
        lastSnapshot,
        LatestSnapshotNotFound
      )
      (genesisOrdinal, genesisHash) <- EitherT.liftF(genesisOrdinalAndHash)
      stage <- EitherT.liftF(nodeState.map(Stage.fromNodeState))

      genesisBlockId = BlockIdentifier(genesisOrdinal, Hex(genesisHash.value))
      currentBlockId = BlockIdentifier(lastSnapshot.ordinal, Hex(lastSnapshot.hash.value))
    } yield
      NetworkStatusResponse(
        currentBlockIdentifier = currentBlockId,
        currentBlockTimestamp = lastSnapshotTimestamp.millisSinceEpoch,
        genesisBlockIdentifier = genesisBlockId,
        oldestBlockIdentifier = genesisBlockId,
        syncStatus = SyncStatus(
          currentIndex = lastSnapshot.ordinal.value.value,
          targetIndex = lastSnapshot.ordinal.value.value,
          stage = stage,
          synced = Stage.isSynced(stage)
        ),
        peers = lastSnapshot.nextFacilitators.map(RosettaPeerId(_)).toList
      )
  }
}
