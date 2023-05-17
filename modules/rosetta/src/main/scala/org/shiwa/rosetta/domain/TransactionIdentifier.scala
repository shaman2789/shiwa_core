package org.shiwa.rosetta.domain

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.security.hash.Hash

import derevo.cats.eqv
import derevo.circe.magnolia.customizableEncoder
import derevo.derive

@derive(eqv, customizableEncoder)
case class TransactionIdentifier(
  hash: Hash
)
