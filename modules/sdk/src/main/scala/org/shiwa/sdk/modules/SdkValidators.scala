package org.shiwa.sdk.modules

import cats.effect.Async

import org.shiwa.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.sdk.domain.block.processing.BlockValidator
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.shiwa.sdk.infrastructure.block.processing.BlockValidator
import org.shiwa.sdk.infrastructure.gossip.RumorValidator
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.SignedValidator

object SdkValidators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    seedlist: Option[Set[PeerId]],
    stateChannelSeedlist: Option[Set[Address]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, SHITransaction]
    val transactionValidator = TransactionValidator.make[F, SHITransaction](signedValidator)
    val blockValidator = BlockValidator.make[F, SHITransaction, SHIBlock](signedValidator, transactionChainValidator, transactionValidator)
    val currencyTransactionChainValidator = TransactionChainValidator.make[F, CurrencyTransaction]
    val currencyTransactionValidator = TransactionValidator.make[F, CurrencyTransaction](signedValidator)
    val currencyBlockValidator = BlockValidator
      .make[F, CurrencyTransaction, CurrencyBlock](signedValidator, currencyTransactionChainValidator, currencyTransactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val stateChannelValidator = StateChannelValidator.make[F](signedValidator, stateChannelSeedlist)

    new SdkValidators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      currencyTransactionChainValidator,
      currencyTransactionValidator,
      blockValidator,
      currencyBlockValidator,
      rumorValidator,
      stateChannelValidator
    ) {}
  }
}

sealed abstract class SdkValidators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F, SHITransaction],
  val transactionValidator: TransactionValidator[F, SHITransaction],
  val currencyTransactionChainValidator: TransactionChainValidator[F, CurrencyTransaction],
  val currencyTransactionValidator: TransactionValidator[F, CurrencyTransaction],
  val blockValidator: BlockValidator[F, SHITransaction, SHIBlock],
  val currencyBlockValidator: BlockValidator[F, CurrencyTransaction, CurrencyBlock],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
