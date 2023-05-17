package org.shiwa.rosetta.http.routes

import cats.effect.Async

import org.shiwa.rosetta.domain.api.network._
import org.shiwa.rosetta.domain.networkapi.NetworkApiService
import org.shiwa.rosetta.ext.http4s.refined._
import org.shiwa.sdk.config.AppEnvironment

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final class NetworkApiRoutes[F[_]: Async](
  appEnvironment: AppEnvironment,
  networkApiService: NetworkApiService[F]
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/network"

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case POST -> Root / "list" =>
      Ok(NetworkApiList.Response(networkApiService.list(appEnvironment))).handleUnknownError

    case req @ POST -> Root / "options" =>
      req.decodeRosettaWithNetworkValidation[NetworkApiOptions.Request](appEnvironment, _.networkIdentifier) { _ =>
        Ok(networkApiService.options).handleUnknownError
      }

    case req @ POST -> Root / "status" =>
      req.decodeRosettaWithNetworkValidation[NetworkApiOptions.Request](appEnvironment, _.networkIdentifier) { _ =>
        networkApiService.status
          .leftMap(_.toRosettaError)
          .asRosettaResponse
      }
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}
