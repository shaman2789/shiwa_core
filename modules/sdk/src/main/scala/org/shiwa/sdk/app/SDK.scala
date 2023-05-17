package org.shiwa.sdk.app

import java.security.KeyPair

import cats.effect.std.{Random, Supervisor}

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.generation.Generation
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.shiwa.sdk.http.p2p.SdkP2PClient
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.modules._
import org.shiwa.sdk.resources.SdkResources
import org.shiwa.security.SecurityProvider

import fs2.concurrent.SignallingRef

trait SDK[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val kryoPool: KryoSerializer[F]
  implicit val metrics: Metrics[F]
  implicit val supervisor: Supervisor[F]

  val keyPair: KeyPair
  lazy val nodeId = PeerId.fromPublic(keyPair.getPublic)
  val generation: Generation
  val seedlist: Option[Set[PeerId]]
  val trustRatings: Option[PeerObservationAdjustmentUpdateBatch]

  val sdkResources: SdkResources[F]
  val sdkP2PClient: SdkP2PClient[F]
  val sdkQueues: SdkQueues[F]
  val sdkStorages: SdkStorages[F]
  val sdkServices: SdkServices[F]
  val sdkPrograms: SdkPrograms[F]
  val sdkValidators: SdkValidators[F]

  def restartSignal: SignallingRef[F, Unit]
}