package org.shiwa.rosetta.domain.api.network

import org.shiwa.ext.derevo.magnoliaCustomizable.snakeCaseConfiguration
import org.shiwa.rosetta.domain.NetworkIdentifier

import derevo.circe.magnolia.customizableDecoder
import derevo.derive

object NetworkApiOptions {
  @derive(customizableDecoder)
  case class Request(networkIdentifier: NetworkIdentifier)
}
