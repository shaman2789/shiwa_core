package org.shiwa.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.domain.statechannel.StateChannelService
import org.shiwa.ext.http4s.AddressVar
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityDecoder, HttpRoutes}

final case class StateChannelRoutes[F[_]: Async](
  stateChannelService: StateChannelService[F]
) extends Http4sDsl[F] {
  private val prefixPath = "/state-channels"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / AddressVar(address) / "snapshot" =>
      req
        .as[Signed[StateChannelSnapshotBinary]]
        .map(StateChannelOutput(address, _))
        .flatMap(stateChannelService.process)
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}