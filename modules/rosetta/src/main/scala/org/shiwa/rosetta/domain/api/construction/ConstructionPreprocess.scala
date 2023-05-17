package org.shiwa.rosetta.domain.api.construction

import cats.data.NonEmptyList

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.rosetta.domain._
import org.shiwa.rosetta.domain.operation.Operation

import derevo.circe.magnolia.{customizableDecoder, customizableEncoder}
import derevo.derive

case object ConstructionPreprocess {
  @derive(customizableDecoder)
  case class Request(
    networkIdentifier: NetworkIdentifier,
    operations: List[Operation]
  )

  @derive(customizableEncoder)
  case class Response(
    requiredPublicKeys: Option[NonEmptyList[AccountIdentifier]]
  )
}
