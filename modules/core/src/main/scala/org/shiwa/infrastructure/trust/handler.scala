package org.shiwa.infrastructure.trust

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.show._

import org.shiwa.domain.trust.storage.TrustStorage
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.gossip.PeerRumor
import org.shiwa.schema.trust.PublicTrust
import org.shiwa.sdk.infrastructure.gossip.{IgnoreSelfOrigin, RumorHandler}

import org.typelevel.log4cats.slf4j.Slf4jLogger

object handler {

  def trustHandler[F[_]: Async: KryoSerializer](
    trustStorage: TrustStorage[F]
  ): RumorHandler[F] = {
    val logger = Slf4jLogger.getLogger[F]

    RumorHandler.fromPeerRumorConsumer[F, PublicTrust](IgnoreSelfOrigin) {
      case PeerRumor(origin, _, trust) =>
        logger.info(s"Received trust=${trust} from id=${origin.show}") >> {
          trustStorage.updatePeerPublicTrustInfo(origin, trust)
        }
    }
  }
}