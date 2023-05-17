package org.shiwa.sdk.infrastructure.genesis

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.functor._

import org.shiwa.sdk.domain.genesis.Loader
import org.shiwa.sdk.domain.genesis.types.GenesisCSVAccount

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.text

object Loader {

  def make[F[_]: Async]: Loader[F] =
    (path: Path) =>
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[GenesisCSVAccount]()
        )
        .map(_.toGenesisAccount)
        .map(_.leftMap(new RuntimeException(_)))
        .rethrow
        .compile
        .toList
        .map(_.toSet)
}
