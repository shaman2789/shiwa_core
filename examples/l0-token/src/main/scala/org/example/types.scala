package org.example

import org.shiwa.kernel.Ω
import org.shiwa.kernel.StateChannelSnapshot
import org.shiwa.security.hash.Hash

object types {

  case class L0TokenTransaction()

  sealed trait L0TokenStep extends Ω
  case class L0TokenBlock(transactions: Set[L0TokenTransaction]) extends L0TokenStep
  case class CreateStateChannelSnapshot() extends L0TokenStep

  case class L0TokenStateChannelSnapshot() extends StateChannelSnapshot {
    val lastSnapshotHash: Hash = ???
  }
}
