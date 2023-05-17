package org.shiwa.sdk.infrastructure.consensus

import org.shiwa.schema.peer.PeerId

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Represents a finished consensus
  */
@derive(eqv, encoder, decoder)
case class ConsensusOutcome[Key, Artifact, Context](
  key: Key,
  facilitators: List[PeerId],
  removedFacilitators: Set[PeerId],
  withdrawnFacilitators: Set[PeerId],
  status: Finished[Artifact, Context]
)