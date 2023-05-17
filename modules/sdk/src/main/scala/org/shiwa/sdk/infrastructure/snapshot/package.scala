package org.shiwa.sdk.infrastructure

import org.shiwa.schema.snapshot.Snapshot
import org.shiwa.schema.transaction.Transaction
import org.shiwa.schema.{Block, SnapshotOrdinal}
import org.shiwa.sdk.infrastructure.consensus.Consensus

package object snapshot {

  type SnapshotArtifact[T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] = S

  type SnapshotConsensus[F[_], T <: Transaction, B <: Block[T], S <: Snapshot[T, B], Context, Event] =
    Consensus[F, Event, SnapshotOrdinal, SnapshotArtifact[T, B, S], Context]

}
