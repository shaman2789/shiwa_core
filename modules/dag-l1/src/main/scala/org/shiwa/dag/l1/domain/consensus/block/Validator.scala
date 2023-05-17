package org.shiwa.dag.l1.domain.consensus.block

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import org.shiwa.dag.l1.domain.block.BlockStorage
import org.shiwa.dag.l1.domain.consensus.block.BlockConsensusInput.PeerBlockConsensusInput
import org.shiwa.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.shiwa.dag.l1.domain.transaction.TransactionStorage
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.Block
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.node.NodeState.Ready
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.Transaction
import org.shiwa.sdk.domain.cluster.storage.ClusterStorage
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.security.SecurityProvider
import org.shiwa.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosInt
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Validator {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  def isReadyForBlockConsensus(state: NodeState): Boolean = state == Ready

  private def enoughPeersForConsensus[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getResponsivePeers
      .map(_.filter(p => isReadyForBlockConsensus(p.state)))
      .map(_.size >= peersCount)

  private def enoughTipsForConsensus[F[_]: Monad, B <: Block[_]](
    blockStorage: BlockStorage[F, B],
    tipsCount: PosInt
  ): F[Boolean] =
    blockStorage.getTips(tipsCount).map(_.isDefined)

  private def atLeastOneTransaction[F[_]: Monad, T <: Transaction](
    transactionStorage: TransactionStorage[F, T]
  ): F[Boolean] =
    transactionStorage.countAllowedForConsensus.map(_ >= 1)

  def canStartOwnConsensus[F[_]: Async, T <: Transaction, B <: Block[T]](
    consensusStorage: ConsensusStorage[F, T, B],
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    blockStorage: BlockStorage[F, B],
    transactionStorage: TransactionStorage[F, T],
    peersCount: PosInt,
    tipsCount: PosInt
  ): F[Boolean] =
    for {
      noOwnRoundInProgress <- consensusStorage.ownConsensus.get.map(_.isEmpty)
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForBlockConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
      enoughTips <- enoughTipsForConsensus(blockStorage, tipsCount)
      enoughTxs <- atLeastOneTransaction(transactionStorage)

      res = noOwnRoundInProgress && stateReadyForConsensus && enoughPeers && enoughTips && enoughTxs
      _ <-
        Applicative[F].whenA(!res) {
          val reason = Seq(
            if (!noOwnRoundInProgress) "Own round in progress" else "",
            if (!stateReadyForConsensus) "State not ready for consensus" else "",
            if (!enoughPeers) "Not enough peers" else "",
            if (!enoughTips) "Not enough tips" else "",
            if (!enoughTxs) "No transactions" else ""
          ).filter(_.nonEmpty).mkString(", ")
          logger.debug(s"Cannot start own consensus: ${reason}")
        }
    } yield res

  def isPeerInputValid[F[_]: Async: KryoSerializer: SecurityProvider, T <: Transaction](
    input: Signed[PeerBlockConsensusInput[T]]
  ): F[Boolean] =
    for {
      hasValidSignature <- input.hasValidSignature
      isSignedBy = input.isSignedExclusivelyBy(PeerId._Id.get(input.value.senderId))
    } yield hasValidSignature && isSignedBy
}