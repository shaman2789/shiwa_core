package org.shiwa.schema

import org.shiwa.schema.height.{Height, SubHeight}
import org.shiwa.schema.snapshot.Snapshot
import org.shiwa.security.Hashed
import org.shiwa.security.hash.{Hash, ProofsHash}

import derevo.cats.show
import derevo.derive

@derive(show)
case class SnapshotReference(
  height: Height,
  subHeight: SubHeight,
  ordinal: SnapshotOrdinal,
  lastSnapshotHash: Hash,
  hash: Hash,
  proofsHash: ProofsHash
)

object SnapshotReference {

  def fromHashedSnapshot(snapshot: Hashed[Snapshot[_, _]]): SnapshotReference =
    SnapshotReference(
      snapshot.height,
      snapshot.subHeight,
      snapshot.ordinal,
      snapshot.lastSnapshotHash,
      snapshot.hash,
      snapshot.proofsHash
    )
}
