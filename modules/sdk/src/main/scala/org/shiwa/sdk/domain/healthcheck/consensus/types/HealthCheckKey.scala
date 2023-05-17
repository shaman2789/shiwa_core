package org.shiwa.sdk.domain.healthcheck.consensus.types

import org.shiwa.schema.peer.PeerId

trait HealthCheckKey {
  def id: PeerId
}
