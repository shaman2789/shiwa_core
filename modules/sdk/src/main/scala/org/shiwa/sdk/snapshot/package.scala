package org.shiwa.sdk

import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo}
import org.shiwa.schema.{Block, SnapshotOrdinal}
import org.shiwa.sdk.infrastructure.consensus.Consensus
import org.shiwa.security.signature.Signed

package object snapshot {

  type SnapshotEvent = Signed[Block[_]]

  type SnapshotKey = SnapshotOrdinal

  type SnapshotArtifact[S <: Snapshot[_, _]] = S

  type SnapshotContext[C <: SnapshotInfo[_]] = C

  type SnapshotConsensus[F[_], S <: Snapshot[_, _], C <: SnapshotInfo[_]] = Consensus[F, SnapshotEvent, SnapshotKey, SnapshotArtifact[S], C]

}
