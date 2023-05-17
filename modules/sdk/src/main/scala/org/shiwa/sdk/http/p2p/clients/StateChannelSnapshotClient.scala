package org.shiwa.sdk.http.p2p.clients

import cats.effect.Async

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.sdk.http.p2p.PeerResponse
import org.shiwa.sdk.http.p2p.PeerResponse.PeerResponse
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelSnapshotBinary

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait StateChannelSnapshotClient[F[_]] {
  def send(data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean]
}

object StateChannelSnapshotClient {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    client: Client[F],
    identifier: Address
  ): StateChannelSnapshotClient[F] =
    new StateChannelSnapshotClient[F] {

      def send(data: Signed[StateChannelSnapshotBinary]): PeerResponse[F, Boolean] =
        PeerResponse(s"state-channels/${identifier.value.value}/snapshot", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data))
        }
    }
}
