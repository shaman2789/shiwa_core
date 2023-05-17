package org.shiwa.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.rosetta.domain.operation.Operation
import org.shiwa.rosetta.domain.{NetworkIdentifier, SigningPayload}
import org.shiwa.security.hex.Hex

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionPayloads {

  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    operations: NonEmptyList[Operation],
    metadata: ConstructionMetadata.Response
  )

  @derive(customizableEncoder, eqv, show)
  case class PayloadsResult(
    unsignedTransaction: Hex,
    payloads: NonEmptyList[SigningPayload]
  )

}
