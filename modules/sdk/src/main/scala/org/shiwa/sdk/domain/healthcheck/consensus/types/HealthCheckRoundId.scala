package org.shiwa.sdk.domain.healthcheck.consensus.types

import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.healthcheck.consensus.types.types.RoundId

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(show, encoder, decoder)
final case class HealthCheckRoundId(roundId: RoundId, owner: PeerId)
