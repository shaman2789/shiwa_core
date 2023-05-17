package org.shiwa.dag.l1.domain.consensus.block.http.p2p.clients

import cats.effect.Sync
import cats.syntax.functor._

import org.shiwa.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.http.p2p.PeerResponse
import org.shiwa.sdk.http.p2p.PeerResponse.PeerResponse
import org.shiwa.security.signature.Signed

import io.circe.Encoder
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait BlockConsensusClient[F[_], T <: Transaction] {
  def sendConsensusData(data: Signed[PeerBlockConsensusInput[T]]): PeerResponse[F, Unit]
}

object BlockConsensusClient {

  def make[F[_]: Sync, T <: Transaction: Encoder](client: Client[F]): BlockConsensusClient[F, T] =
    new BlockConsensusClient[F, T] {

      def sendConsensusData(data: Signed[PeerBlockConsensusInput[T]]): PeerResponse[F, Unit] =
        PeerResponse("consensus/data", POST)(client) { (req, c) =>
          c.successful(req.withEntity(data)).void
        }
    }
}