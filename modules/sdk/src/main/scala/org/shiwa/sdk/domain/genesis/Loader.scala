package org.shiwa.sdk.domain.genesis

import org.shiwa.sdk.domain.genesis.types.GenesisAccount

import fs2.io.file.Path

trait Loader[F[_]] {
  def load(path: Path): F[Set[GenesisAccount]]
}
