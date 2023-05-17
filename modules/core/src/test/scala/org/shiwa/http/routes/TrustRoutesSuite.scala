package org.shiwa.http.routes

import cats.effect._
import cats.effect.unsafe.implicits.global

import scala.reflect.runtime.universe.TypeTag

import org.shiwa.coreKryoRegistrar
import org.shiwa.domain.cluster.programs.TrustPush
import org.shiwa.ext.kryo._
import org.shiwa.infrastructure.trust.storage.TrustStorage
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.generators._
import org.shiwa.schema.trust.{PeerObservationAdjustmentUpdate, PeerObservationAdjustmentUpdateBatch}
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.sdkKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.Encoder
import org.http4s.Method._
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.syntax.literals._
import suite.HttpSuite

object TrustRoutesSuite extends HttpSuite {
  test("GET trust succeeds") {
    val req = GET(uri"/trust")
    val peer = (for {
      peers <- peersGen()
    } yield peers.head).sample.get

    KryoSerializer
      .forAsync[IO](sdkKryoRegistrar.union(coreKryoRegistrar))
      .use { implicit kryoPool =>
        for {
          ts <- TrustStorage.make[IO]
          gossip = new Gossip[IO] {
            override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
            override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
          }
          tp = TrustPush.make[IO](ts, gossip)
          _ <- ts.updateTrust(
            PeerObservationAdjustmentUpdateBatch(List(PeerObservationAdjustmentUpdate(peer.id, 0.5)))
          )
          routes = TrustRoutes[IO](ts, tp).p2pRoutes
        } yield expectHttpStatus(routes, req)(Status.Ok)
      }
      .unsafeRunSync()
  }
}
