package org.shiwa.currency.l0.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.shiwa.currency.l0.http.P2PClient
import org.shiwa.currency.l0.snapshot.programs.{Download, Genesis, Rollback}
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.address.Address
import org.shiwa.schema.peer.{L0Peer, PeerId}
import org.shiwa.sdk.domain.cluster.programs.{Joining, L0PeerDiscovery, PeerDiscovery}
import org.shiwa.sdk.domain.snapshot.PeerSelect
import org.shiwa.sdk.domain.snapshot.programs.Download
import org.shiwa.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.shiwa.sdk.infrastructure.snapshot.{CurrencySnapshotContextFunctions, MajorityPeerSelect}
import org.shiwa.sdk.modules.SdkPrograms
import org.shiwa.security.SecurityProvider

object Programs {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    nodeId: PeerId,
    identifier: Address,
    globalL0Peer: L0Peer,
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    p2pClient: P2PClient[F],
    currencySnapshotContextFns: CurrencySnapshotContextFunctions[F]
  ): Programs[F] = {
    val peerSelect: PeerSelect[F] = MajorityPeerSelect.make(storages.cluster, p2pClient.l0GlobalSnapshot)
    val download = Download
      .make(
        p2pClient,
        storages.cluster,
        currencySnapshotContextFns,
        storages.node,
        services.consensus,
        peerSelect
      )

    val globalL0PeerDiscovery = L0PeerDiscovery.make(
      p2pClient.globalL0Cluster,
      storages.globalL0Cluster
    )

    val genesisLoader = GenesisLoader.make

    val genesis = Genesis.make(
      keyPair,
      services.collateral,
      storages.lastSignedBinaryHash,
      services.stateChannelSnapshot,
      storages.snapshot,
      p2pClient.stateChannelSnapshot,
      globalL0Peer,
      nodeId,
      services.consensus.manager,
      genesisLoader
    )

    val rollback = Rollback.make(
      nodeId,
      identifier,
      services.globalL0,
      storages.lastSignedBinaryHash,
      storages.snapshot,
      services.collateral,
      services.consensus.manager
    )

    new Programs[F](sdkPrograms.peerDiscovery, globalL0PeerDiscovery, sdkPrograms.joining, download, genesis, rollback) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val globalL0PeerDiscovery: L0PeerDiscovery[F],
  val joining: Joining[F],
  val download: Download[F],
  val genesis: Genesis[F],
  val rollback: Rollback[F]
)