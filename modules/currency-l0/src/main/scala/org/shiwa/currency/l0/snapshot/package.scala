package org.shiwa.currency.l0

import org.shiwa.currency.schema.currency._
import org.shiwa.sdk.infrastructure.snapshot._
import org.shiwa.security.signature.Signed

package object snapshot {

  type CurrencySnapshotEvent = Signed[CurrencyBlock]

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot

  type CurrencySnapshotContext = CurrencySnapshotInfo

  type CurrencySnapshotConsensus[F[_]] =
    SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshotArtifact, CurrencySnapshotContext, CurrencySnapshotEvent]

}
