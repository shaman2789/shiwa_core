package org.shiwa.currency.l0.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.currency.l0.config.types.AppConfig
import org.shiwa.currency.l0.http.P2PClient
import org.shiwa.currency.l0.snapshot.services.{Rewards, StateChannelSnapshotService}
import org.shiwa.currency.l0.snapshot.{CurrencySnapshotConsensus, CurrencySnapshotEvent}
import org.shiwa.currency.schema.currency._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.cluster.services.{Cluster, Session}
import org.shiwa.sdk.domain.collateral.Collateral
import org.shiwa.sdk.domain.gossip.Gossip
import org.shiwa.sdk.domain.healthcheck.LocalHealthcheck
import org.shiwa.sdk.domain.snapshot.services.{AddressService, GlobalL0Service}
import org.shiwa.sdk.infrastructure.Collateral
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot.services.AddressService
import org.shiwa.sdk.infrastructure.snapshot.{CurrencySnapshotContextFunctions, SnapshotConsensus}
import org.shiwa.sdk.modules.SdkServices
import org.shiwa.security.SecurityProvider

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    p2PClient: P2PClient[F],
    sdkServices: SdkServices[F],
    storages: Storages[F],
    validators: Validators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    identifier: Address
  ): F[Services[F]] =
    for {
      stateChannelSnapshotService <- StateChannelSnapshotService
        .make[F](keyPair, storages.lastSignedBinaryHash, p2PClient.stateChannelSnapshot, storages.globalL0Cluster, storages.snapshot)
        .pure[F]

      rewards = Rewards.make[F]

      consensus <- CurrencySnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          rewards,
          cfg.snapshot,
          client,
          session,
          stateChannelSnapshotService,
          sdkServices.currencySnapshotAcceptanceManager
        )
      addressService = AddressService.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
      globalL0Service = GlobalL0Service
        .make[F](p2PClient.l0GlobalSnapshot, storages.globalL0Cluster, storages.lastGlobalSnapshot, None)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        stateChannelSnapshot = stateChannelSnapshotService,
        globalL0 = globalL0Service,
        snapshotContextFunctions = sdkServices.currencySnapshotContextFns
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: SnapshotConsensus[
    F,
    CurrencyTransaction,
    CurrencyBlock,
    CurrencyIncrementalSnapshot,
    CurrencySnapshotInfo,
    CurrencySnapshotEvent
  ],
  val address: AddressService[F, CurrencyIncrementalSnapshot],
  val collateral: Collateral[F],
  val stateChannelSnapshot: StateChannelSnapshotService[F],
  val globalL0: GlobalL0Service[F],
  val snapshotContextFunctions: CurrencySnapshotContextFunctions[F]
)
