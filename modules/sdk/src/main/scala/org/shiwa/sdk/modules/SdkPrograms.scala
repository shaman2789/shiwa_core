package org.shiwa.sdk.modules

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.config.types.SdkConfig
import org.shiwa.sdk.domain.cluster.programs.{Joining, PeerDiscovery}
import org.shiwa.sdk.domain.healthcheck.LocalHealthcheck
import org.shiwa.sdk.http.p2p.clients.{ClusterClient, SignClient}
import org.shiwa.security.SecurityProvider
import org.shiwa.security.hash.Hash

object SdkPrograms {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    cfg: SdkConfig,
    storages: SdkStorages[F],
    services: SdkServices[F],
    clusterClient: ClusterClient[F],
    signClient: SignClient[F],
    localHealthcheck: LocalHealthcheck[F],
    seedlist: Option[Set[PeerId]],
    nodeId: PeerId,
    versionHash: Hash
  ): F[SdkPrograms[F]] =
    for {
      pd <- PeerDiscovery.make(clusterClient, storages.cluster, nodeId)
      joining <- Joining.make(
        cfg.environment,
        storages.node,
        storages.cluster,
        signClient,
        services.cluster,
        services.session,
        storages.session,
        localHealthcheck,
        seedlist,
        nodeId,
        cfg.stateAfterJoining,
        pd,
        versionHash
      )
    } yield new SdkPrograms[F](pd, joining) {}
}

sealed abstract class SdkPrograms[F[_]] private (
  val peerDiscovery: PeerDiscovery[F],
  val joining: Joining[F]
)
