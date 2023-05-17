package org.shiwa.rosetta.domain.api.network

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableEncoder
import derevo.derive

object NetworkApiList {
  @derive(customizableEncoder)
  case class Response(networkIdentifiers: List[NetworkIdentifier])
}
