package org.shiwa.sdk.resources

import java.security.PrivateKey

import cats.effect.{Async, Resource}

import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.config.types.SdkConfig
import org.shiwa.sdk.domain.cluster.storage.SessionStorage
import org.shiwa.sdk.http.p2p.middleware.PeerAuthMiddleware
import org.shiwa.security.SecurityProvider

import org.http4s.client.Client
import org.http4s.client.middleware.{RequestLogger, ResponseLogger}

sealed abstract class SdkResources[F[_]](
  val client: Client[F]
)

object SdkResources {

  def make[F[_]: MkHttpClient: Async: SecurityProvider](
    cfg: SdkConfig,
    privateKey: PrivateKey,
    sessionStorage: SessionStorage[F],
    selfId: PeerId
  ): Resource[F, SdkResources[F]] =
    MkHttpClient[F]
      .newEmber(cfg.httpConfig.client)
      .map(
        PeerAuthMiddleware.requestSignerMiddleware[F](_, privateKey, sessionStorage, selfId)
      )
      .map { client =>
        ResponseLogger(logHeaders = true, logBody = false)(RequestLogger(logHeaders = true, logBody = false)(client))
      }
      .map(new SdkResources[F](_) {})
}
