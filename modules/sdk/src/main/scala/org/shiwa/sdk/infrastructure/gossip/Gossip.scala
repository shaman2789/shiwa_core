package org.shiwa.sdk.infrastructure.gossip

import java.security.KeyPair

import cats.effect.std.Queue
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.reflect.runtime.universe.TypeTag

import org.shiwa.ext.cats.syntax.next._
import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.generation.Generation
import org.shiwa.schema.gossip._
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.Encoder
import io.circe.syntax._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Gossip {

  def make[F[_]: Async: SecurityProvider: KryoSerializer: Metrics](
    rumorQueue: Queue[F, Hashed[RumorRaw]],
    selfId: PeerId,
    generation: Generation,
    keyPair: KeyPair
  ): F[Gossip[F]] =
    Ref.of[F, Counter](Counter.MinValue).map { counter =>
      new Gossip[F] {

        private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

        def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            count <- counter.getAndUpdate(_.next)
            rumor = PeerRumorRaw(selfId, Ordinal(generation, count), contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
          for {
            contentJson <- rumorContent.asJson.pure[F]
            rumor = CommonRumorRaw(contentJson, ContentType.of[A])
            _ <- signAndOffer(rumor)
          } yield ()

        private def signAndOffer(rumor: RumorRaw): F[Unit] =
          for {
            signedRumor <- rumor.sign(keyPair)
            hashedRumor <- signedRumor.toHashed
            _ <- rumorQueue.offer(hashedRumor)
            _ <- metrics.updateRumorsSpread(signedRumor)
            _ <- logSpread(hashedRumor)
          } yield ()

        private def logSpread(hashedRumor: Hashed[RumorRaw]): F[Unit] =
          rumorLogger.info(
            s"Rumor spread {hash=${hashedRumor.hash.show}, rumor=${hashedRumor.signed.value.show}"
          )

      }
    }

}
