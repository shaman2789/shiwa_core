package org.shiwa.sdk.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.generation.Generation
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.sdk.config.types.{CollateralConfig, SdkConfig}
import org.shiwa.sdk.domain.cluster.services.{Cluster, Session}
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.domain.healthcheck.LocalHealthcheck
import org.shiwa.sdk.http.p2p.clients.NodeClient
import org.shiwa.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.shiwa.sdk.infrastructure.cluster.services.Cluster
import org.shiwa.sdk.infrastructure.gossip.Gossip
import org.shiwa.sdk.infrastructure.healthcheck.LocalHealthcheck
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot._
import org.shiwa.security.SecurityProvider
import org.shiwa.security.hash.Hash

import fs2.concurrent.SignallingRef

object SdkServices {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    cfg: SdkConfig,
    nodeId: PeerId,
    generation: Generation,
    keyPair: KeyPair,
    storages: SdkStorages[F],
    queues: SdkQueues[F],
    session: Session[F],
    nodeClient: NodeClient[F],
    validators: SdkValidators[F],
    seedlist: Option[Set[PeerId]],
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash,
    collateral: CollateralConfig
  ): F[SdkServices[F]] = {

    val cluster = Cluster
      .make[F](
        cfg.leavingDelay,
        cfg.httpConfig,
        nodeId,
        keyPair,
        storages.cluster,
        storages.session,
        storages.node,
        seedlist,
        restartSignal,
        versionHash
      )

    for {
      localHealthcheck <- LocalHealthcheck.make[F](nodeClient, storages.cluster)
      gossip <- Gossip.make[F](queues.rumor, nodeId, generation, keyPair)
      currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, CurrencyTransaction, CurrencyBlock](validators.currencyBlockValidator),
        collateral.amount
      )
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotAcceptanceManager)
      globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, SHITransaction, SHIBlock](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor.make[F](validators.stateChannelValidator, currencySnapshotContextFns),
        collateral.amount
      )
      globalSnapshotContextFns = GlobalSnapshotContextFunctions.make(globalSnapshotAcceptanceManager)
    } yield
      new SdkServices[F](
        localHealthcheck = localHealthcheck,
        cluster = cluster,
        session = session,
        gossip = gossip,
        globalSnapshotContextFns = globalSnapshotContextFns,
        currencySnapshotContextFns = currencySnapshotContextFns,
        currencySnapshotAcceptanceManager = currencySnapshotAcceptanceManager
      ) {}
  }
}

sealed abstract class SdkServices[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
  val currencySnapshotContextFns: CurrencySnapshotContextFunctions[F],
  val currencySnapshotAcceptanceManager: CurrencySnapshotAcceptanceManager[F]
)
