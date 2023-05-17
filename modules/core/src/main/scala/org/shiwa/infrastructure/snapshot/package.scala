package org.shiwa.infrastructure

import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import org.shiwa.sdk.infrastructure.snapshot.SnapshotConsensus
import org.shiwa.security.signature.Signed
import org.shiwa.statechannel.StateChannelOutput

package object snapshot {

  type SHIEvent = Signed[SHIBlock]

  type StateChannelEvent = StateChannelOutput

  type GlobalSnapshotEvent = Either[StateChannelEvent, SHIEvent]

  type GlobalSnapshotKey = SnapshotOrdinal

  type GlobalSnapshotArtifact = GlobalIncrementalSnapshot

  type GlobalSnapshotContext = GlobalSnapshotInfo

  type GlobalSnapshotConsensus[F[_]] =
    SnapshotConsensus[F, SHITransaction, SHIBlock, GlobalSnapshotArtifact, GlobalSnapshotContext, GlobalSnapshotEvent]

}
