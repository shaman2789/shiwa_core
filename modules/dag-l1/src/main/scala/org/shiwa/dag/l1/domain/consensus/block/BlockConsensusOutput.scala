package org.shiwa.dag.l1.domain.consensus.block

import org.shiwa.dag.l1.domain.consensus.round.RoundId
import org.shiwa.kernel.Ω
import org.shiwa.schema.Block
import org.shiwa.security.Hashed

sealed trait BlockConsensusOutput[+B <: Block[_]] extends Ω

object BlockConsensusOutput {
  case class FinalBlock[B <: Block[_]](hashedBlock: Hashed[B]) extends BlockConsensusOutput[B]
  case class CleanedConsensuses(ids: Set[RoundId]) extends BlockConsensusOutput[Nothing]
  case object NoData extends BlockConsensusOutput[Nothing]
}
