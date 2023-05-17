package org.shiwa.rosetta.http.routes

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Async
import cats.syntax.either._

import org.shiwa.rosetta.domain.api.construction._
import org.shiwa.rosetta.domain.construction.ConstructionService
import org.shiwa.rosetta.domain.error.{ConstructionError, UnsupportedOperation}
import org.shiwa.rosetta.ext.http4s.refined._
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.security.hex.Hex

import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final class ConstructionRoutes[F[_]: Async](
  constructionService: ConstructionService[F],
  appEnvironment: AppEnvironment
) extends Http4sDsl[F] {

  private[routes] val prefixPath = "/construction"

  private val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "derive" =>
      req.decodeRosettaWithNetworkValidation[ConstructionDerive.Request](appEnvironment, _.networkIdentifier) { deriveReq =>
        constructionService
          .derive(deriveReq.publicKey)
          .bimap(_.toRosettaError, ConstructionDerive.Response(_))
          .asRosettaResponse
      }

    case req @ POST -> Root / "hash" =>
      req.decodeRosettaWithNetworkValidation[ConstructionHash.Request](appEnvironment, _.networkIdentifier) { hashReq =>
        constructionService
          .getTransactionIdentifier(hashReq.signedTransaction)
          .bimap(_.toRosettaError, ConstructionHash.Response(_))
          .asRosettaResponse
          .handleUnknownError
      }

    case req @ POST -> Root / "preprocess" =>
      req.decodeRosettaWithNetworkValidation[ConstructionPreprocess.Request](appEnvironment, _.networkIdentifier) { preprocessReq =>
        val accountIdentifiers = constructionService
          .getAccountIdentifiers(preprocessReq.operations)

        Ok(ConstructionPreprocess.Response(accountIdentifiers)).handleUnknownError
      }

    case req @ POST -> Root / "parse" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionParse.Request](appEnvironment, _.networkIdentifier) { parseReq =>
          constructionService
            .parseTransaction(parseReq.transaction, parseReq.signed)
            .bimap(
              _.toRosettaError,
              ConstructionParse.Response.fromParseResult
            )
            .asRosettaResponse
        }
        .handleUnknownError

    case req @ POST -> Root / "combine" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionCombine.Request](appEnvironment, _.networkIdentifier) { combineReq =>
          val combinedHex = combineReq.signatures match {
            case NonEmptyList(head, Nil) =>
              constructionService
                .combineTransaction(combineReq.unsignedTransaction, head)
            case _ => EitherT.fromEither(UnsupportedOperation.asInstanceOf[ConstructionError].asLeft[Hex])
          }

          combinedHex.bimap(_.toRosettaError, ConstructionCombine.Response(_)).asRosettaResponse.handleUnknownError
        }

    case req @ POST -> Root / "metadata" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionMetadata.Request](appEnvironment, _.networkIdentifier) { metadataReq =>
          constructionService
            .getMetadata(metadataReq.publicKeys)
            .bimap(
              _.toRosettaError,
              ConstructionMetadata.Response.fromMetadataResult
            )
            .asRosettaResponse
        }
        .handleUnknownError

    case req @ POST -> Root / "payloads" =>
      req
        .decodeRosettaWithNetworkValidation[ConstructionPayloads.Request](appEnvironment, _.networkIdentifier) { payloadsReq =>
          constructionService
            .getPayloads(payloadsReq.operations, ConstructionMetadata.MetadataResult.fromResponse(payloadsReq.metadata))
            .leftMap(_.toRosettaError)
            .asRosettaResponse
        }
        .handleUnknownError
  }

  val routes: HttpRoutes[F] = Router(
    prefixPath -> public
  )
}