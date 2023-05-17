package org.example

import org.shiwa.kernel.StateChannelSnapshot
import org.shiwa.security.hash.Hash

case class SimpleSnapshot(lastSnapshotHash: Hash) extends StateChannelSnapshot
