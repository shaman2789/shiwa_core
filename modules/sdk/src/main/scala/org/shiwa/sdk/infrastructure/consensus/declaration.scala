package org.shiwa.sdk.infrastructure.consensus

import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary

object declaration {

  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(upperBound: Bound, candidates: Set[PeerId], trigger: Option[ConsensusTrigger], facilitatorsHash: Hash)
      extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(hash: Hash, facilitatorsHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(signature: Signature, facilitatorsHash: Hash) extends PeerDeclaration

  object kind {

    @derive(arbitrary, eqv, show, encoder, decoder)
    sealed trait PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Facility extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object Proposal extends PeerDeclarationKind

    @derive(eqv, show, encoder, decoder)
    case object MajoritySignature extends PeerDeclarationKind

  }

}
