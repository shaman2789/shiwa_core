package org.shiwa.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._

import org.shiwa.dag.l1.domain.address.storage.AddressStorage
import org.shiwa.dag.l1.domain.block.BlockStorage
import org.shiwa.dag.l1.domain.transaction.TransactionStorage
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.shiwa.sdk.domain.snapshot.SnapshotContextFunctions
import org.shiwa.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.shiwa.security.signature.Signed
import org.shiwa.security.{Hashed, SecurityProvider}

object SHISnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, SHIBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    transactionStorage: TransactionStorage[F, SHITransaction],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): SnapshotProcessor[F, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotProcessor[F, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      import SnapshotProcessor._

      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      ): F[SnapshotProcessingResult] =
        checkAlignment(snapshot, blockStorage, lastGlobalSnapshotStorage)
          .flatMap(processAlignment(_, blockStorage, transactionStorage, lastGlobalSnapshotStorage, addressStorage))

      def applySnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: GlobalIncrementalSnapshot,
        snapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = applyGlobalSnapshotFn(lastState, lastSnapshot, snapshot)

      def applyGlobalSnapshotFn(
        lastGlobalState: GlobalSnapshotInfo,
        lastGlobalSnapshot: GlobalIncrementalSnapshot,
        globalSnapshot: Signed[GlobalIncrementalSnapshot]
      ): F[GlobalSnapshotInfo] = globalSnapshotContextFns.createContext(lastGlobalState, lastGlobalSnapshot, globalSnapshot)
    }
}
