package org.shiwa.dag.l1.modules

import cats.effect.Async

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.address.Address
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.block.processing.BlockValidator
import org.shiwa.sdk.domain.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.shiwa.sdk.infrastructure.block.processing.BlockValidator
import org.shiwa.sdk.infrastructure.gossip.RumorValidator
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.SignedValidator

object Validators {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider,
    T <: Transaction,
    B <: Block[T],
    P <: StateProof,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, T, B, P, S, SI],
    seedlist: Option[Set[PeerId]]
  ): Validators[F, T, B] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, T]
    val transactionValidator = TransactionValidator.make[F, T](signedValidator)
    val blockValidator =
      BlockValidator.make[F, T, B](signedValidator, transactionChainValidator, transactionValidator)
    val contextualTransactionValidator = ContextualTransactionValidator.make[F, T](
      transactionValidator,
      (address: Address) => storages.transaction.getLastAcceptedReference(address)
    )
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F, T, B](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_], T <: Transaction, B <: Block[T]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F, T, B],
  val transaction: TransactionValidator[F, T],
  val transactionContextual: ContextualTransactionValidator[F, T],
  val rumorValidator: RumorValidator[F]
)
