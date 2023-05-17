package org.shiwa.schema

import cats.effect.{IO, Resource}

import org.shiwa.ext.crypto.RefinedHashableF
import org.shiwa.kryo.KryoSerializer
import org.shiwa.shared.sharedKryoRegistrar

import suite.ResourceSuite
import weaver.scalacheck.Checkers

object CoinbaseSuite extends ResourceSuite with Checkers {

  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, KryoSerializer[IO]] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("coinbase hash should be constant and known") { implicit kp =>
    Coinbase.value.hashF.map(
      expect.same(
        Coinbase.hash,
        _
      )
    )
  }
}
