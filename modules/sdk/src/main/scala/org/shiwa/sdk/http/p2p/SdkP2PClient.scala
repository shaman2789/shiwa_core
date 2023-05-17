package org.shiwa.sdk.http.p2p

import cats.effect.Async

import org.shiwa.kryo.KryoSerializer
import org.shiwa.sdk.domain.cluster.services.Session
import org.shiwa.sdk.http.p2p.clients._
import org.shiwa.sdk.infrastructure.gossip.p2p.GossipClient
import org.shiwa.security.SecurityProvider

import org.http4s.client._

object SdkP2PClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](client: Client[F], session: Session[F]): SdkP2PClient[F] =
    new SdkP2PClient[F](
      SignClient.make[F](client),
      ClusterClient.make[F](client, session),
      GossipClient.make[F](client, session),
      NodeClient.make[F](client, session),
      TrustClient.make[F](client, session),
      L0GlobalSnapshotClient.make[F](client)
    ) {}

}

sealed abstract class SdkP2PClient[F[_]] private (
  val sign: SignClient[F],
  val cluster: ClusterClient[F],
  val gossip: GossipClient[F],
  val node: NodeClient[F],
  val trust: TrustClient[F],
  val l0GlobalSnapshot: L0GlobalSnapshotClient[F]
)