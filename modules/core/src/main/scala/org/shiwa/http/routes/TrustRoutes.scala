package org.shiwa.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.shiwa.domain.cluster.programs.TrustPush
import org.shiwa.domain.trust.storage.TrustStorage
import org.shiwa.ext.codecs.BinaryCodec._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.shiwa.sdk.ext.http4s.refined.RefinedRequestDecoder

import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class TrustRoutes[F[_]: Async: KryoSerializer](
  trustStorage: TrustStorage[F],
  trustPush: TrustPush[F]
) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/trust"

  private val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      trustStorage.getPublicTrust.flatMap { publicTrust =>
        Ok(publicTrust)
      }
  }

  private val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req.decodeR[PeerObservationAdjustmentUpdateBatch] { trustUpdates =>
        trustStorage
          .updateTrust(trustUpdates)
          .flatMap(_ => trustPush.publishUpdated())
          .flatMap(_ => Ok())
          .recoverWith {
            case _ =>
              Conflict(s"Internal trust update failure")
          }
      }
  }

  val p2pRoutes: HttpRoutes[F] = Router(
    prefixPath -> p2p
  )

  val cliRoutes: HttpRoutes[F] = Router(
    prefixPath -> cli
  )
}