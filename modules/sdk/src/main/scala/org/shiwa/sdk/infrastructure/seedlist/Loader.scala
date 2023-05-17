package org.shiwa.sdk.infrastructure.seedlist

import cats.effect.Async
import cats.syntax.functor._

import org.shiwa.cli.env.SeedListPath
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.infrastructure.seedlist.types.SeedlistCSVEntry

import fs2.data.csv._
import fs2.io.file.Files
import fs2.text
import io.estatico.newtype.ops._

trait Loader[F[_]] {
  def load(path: SeedListPath): F[Set[PeerId]]
}

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: SeedListPath) =>
      Files[F]
        .readAll(path.coerce)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[SeedlistCSVEntry]()
        )
        .map(_.toPeerId)
        .compile
        .toList
        .map(_.toSet)
}
