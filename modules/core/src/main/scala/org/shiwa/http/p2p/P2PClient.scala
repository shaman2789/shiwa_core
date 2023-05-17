package org.shiwa.http.p2p

import cats.effect.Async

import org.shiwa.kryo.KryoSerializer
import org.shiwa.sdk.http.p2p.SdkP2PClient
import org.shiwa.sdk.http.p2p.clients._
import org.shiwa.sdk.infrastructure.gossip.p2p.GossipClient
import org.shiwa.security.SecurityProvider

import org.http4s.client.Client

object P2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    sdkP2PClient: SdkP2PClient[F],
    client: Client[F]
  ): P2PClient[F] =
    new P2PClient[F](
      sdkP2PClient.sign,
      sdkP2PClient.cluster,
      sdkP2PClient.gossip,
      sdkP2PClient.node,
      sdkP2PClient.l0GlobalSnapshot
    ) {}
}

sealed abstract class P2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val globalSnapshot: L0GlobalSnapshotClient[F]
)
