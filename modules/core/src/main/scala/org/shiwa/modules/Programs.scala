package org.shiwa.modules

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.Random

import org.shiwa.config.types.AppConfig
import org.shiwa.domain.cluster.programs.TrustPush
import org.shiwa.domain.snapshot.programs.Download
import org.shiwa.http.p2p.P2PClient
import org.shiwa.infrastructure.snapshot.programs.RollbackLoader
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.shiwa.sdk.domain.snapshot.PeerSelect
import org.shiwa.sdk.domain.snapshot.programs.Download
import org.shiwa.sdk.infrastructure.snapshot.{GlobalSnapshotContextFunctions, MajorityPeerSelect}
import org.shiwa.sdk.modules.SdkPrograms
import org.shiwa.security.SecurityProvider

object Programs {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Random](
    sdkPrograms: SdkPrograms[F],
    storages: Storages[F],
    services: Services[F],
    keyPair: KeyPair,
    config: AppConfig,
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    p2pClient: P2PClient[F],
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F]
  ): Programs[F] = {
    val trustPush = TrustPush.make(storages.trust, services.gossip)
    val peerSelect: PeerSelect[F] = MajorityPeerSelect.make(storages.cluster, p2pClient.globalSnapshot)
    val download: Download[F] = Download
      .make[F](
        storages.snapshotDownload,
        p2pClient,
        storages.cluster,
        lastFullGlobalSnapshotOrdinal,
        globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
        storages.node,
        services.consensus,
        peerSelect
      )
    val rollbackLoader = RollbackLoader.make(
      keyPair,
      config.snapshot,
      storages.incrementalGlobalSnapshotLocalFileSystemStorage,
      services.snapshotContextFunctions
    )

    new Programs[F](sdkPrograms.peerDiscovery, sdkPrograms.joining, trustPush, download, rollbackLoader) {}
  }
}

sealed abstract class Programs[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F],
  val trustPush: TrustPush[F],
  val download: Download[F],
  val rollbackLoader: RollbackLoader[F]
)
