package org.shiwa.rosetta.domain.api.construction

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.rosetta.domain.{NetworkIdentifier, TransactionIdentifier}
import org.shiwa.security.hex.Hex

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionHash {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    signedTransaction: Hex
  )

  @derive(customizableEncoder)
  case class Response(
    transactionIdentifier: TransactionIdentifier
  )
}
