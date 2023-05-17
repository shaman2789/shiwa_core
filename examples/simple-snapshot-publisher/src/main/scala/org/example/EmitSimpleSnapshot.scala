package org.example

import org.shiwa.kernel.Ω
import org.shiwa.security.hash.Hash

case class EmitSimpleSnapshot(lastSnapshotHash: Hash) extends Ω
