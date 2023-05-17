package org.shiwa.dag.l1.domain.consensus.block

import java.security.KeyPair

import org.shiwa.dag.l1.domain.block.BlockStorage
import org.shiwa.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.shiwa.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.shiwa.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.shiwa.dag.l1.domain.transaction.TransactionStorage
import org.shiwa.schema.Block
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.block.processing.BlockValidator
import org.shiwa.sdk.domain.cluster.storage.ClusterStorage
import org.shiwa.sdk.domain.transaction.TransactionValidator

case class BlockConsensusContext[F[_], T <: Transaction, B <: Block[T]](
  blockConsensusClient: BlockConsensusClient[F, T],
  blockStorage: BlockStorage[F, B],
  blockValidator: BlockValidator[F, T, B],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F, T, B],
  keyPair: KeyPair,
  selfId: PeerId,
  transactionStorage: TransactionStorage[F, T],
  transactionValidator: TransactionValidator[F, T]
)
