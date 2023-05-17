package org.shiwa.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.config.types.AppConfig
import org.shiwa.domain.cell.L0Cell
import org.shiwa.domain.statechannel.StateChannelService
import org.shiwa.infrastructure.rewards._
import org.shiwa.infrastructure.snapshot._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.peer.PeerId
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import org.shiwa.sdk.domain.cluster.services.{Cluster, Session}
import org.shiwa.sdk.domain.collateral.Collateral
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.domain.healthcheck.LocalHealthcheck
import org.shiwa.sdk.domain.rewards.Rewards
import org.shiwa.sdk.domain.snapshot.services.AddressService
import org.shiwa.sdk.infrastructure.Collateral
import org.shiwa.sdk.infrastructure.block.processing.BlockAcceptanceManager
import org.shiwa.sdk.infrastructure.consensus.Consensus
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot.services.AddressService
import org.shiwa.sdk.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotContextFunctions,
  GlobalSnapshotStateChannelEventsProcessor
}
import org.shiwa.sdk.modules.{SdkServices, SdkValidators}
import org.shiwa.security.SecurityProvider

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: SdkValidators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          cfg.rewards.rewardsPerEpoch,
          ProgramsDistributor.make(cfg.rewards.programs),
          FacilitatorDistributor.make
        )
        .pure[F]
      snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[F, SHITransaction, SHIBlock](validators.blockValidator),
        GlobalSnapshotStateChannelEventsProcessor.make[F](validators.stateChannelValidator, sdkServices.currencySnapshotContextFns),
        cfg.collateral.amount
      )
      consensus <- GlobalSnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          snapshotAcceptanceManager,
          cfg.snapshot,
          cfg.environment,
          client,
          session,
          rewards
        )
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput), validators.stateChannelValidator)
      snapshotContextFunctions = GlobalSnapshotContextFunctions.make(snapshotAcceptanceManager)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        rewards = rewards,
        stateChannel = stateChannelService,
        snapshotContextFunctions = snapshotContextFunctions
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: Consensus[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact, GlobalSnapshotContext],
  val address: AddressService[F, GlobalIncrementalSnapshot],
  val collateral: Collateral[F],
  val rewards: Rewards[F, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot],
  val stateChannel: StateChannelService[F],
  val snapshotContextFunctions: GlobalSnapshotContextFunctions[F]
)
