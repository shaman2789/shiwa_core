package org.shiwa.dag.l1.http.p2p

import org.shiwa.schema.Block
import org.shiwa.sdk.http.p2p.PeerResponse
import org.shiwa.sdk.http.p2p.PeerResponse.PeerResponse
import org.shiwa.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0CurrencyClusterClient[F[_], B <: Block[_]] {
  def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean]
}

object L0CurrencyClusterClient {

  def make[F[_], B <: Block[_]: Encoder](pathPrefix: String, client: Client[F]): L0CurrencyClusterClient[F, B] =
    new L0CurrencyClusterClient[F, B] {

      def sendL1Output(output: Signed[B]): PeerResponse[F, Boolean] =
        PeerResponse(s"$pathPrefix/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
