package org.shiwa.sdk.domain.snapshot.services

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._

import org.shiwa.ext.cats.syntax.next._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.sdk.domain.cluster.storage.L0ClusterStorage
import org.shiwa.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.shiwa.sdk.http.p2p.PeerResponse
import org.shiwa.sdk.http.p2p.clients.L0GlobalSnapshotClient
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed
import org.shiwa.security.{Hashed, SecurityProvider}

import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalL0Service[F[_]] {
  def pullLatestSnapshot: F[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
  def pullGlobalSnapshots: F[Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalIncrementalSnapshot]]]]
  def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]]
  def pullGlobalSnapshot(hash: Hash): F[Option[Hashed[GlobalIncrementalSnapshot]]]
}

object GlobalL0Service {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider
  ](
    l0GlobalSnapshotClient: L0GlobalSnapshotClient[F],
    globalL0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    singlePullLimit: Option[PosLong]
  ): GlobalL0Service[F] =
    new GlobalL0Service[F] {

      private val logger = Slf4jLogger.getLogger[F]

      def pullLatestSnapshot: F[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          l0GlobalSnapshotClient.getLatest(l0Peer).flatMap {
            case ((snapshot, state)) =>
              snapshot.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map((_, state))
          }
        }

      def pullGlobalSnapshot(hash: Hash): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        pullGlobalSnapshot(l0GlobalSnapshotClient.get(hash)).handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with hash=$hash")
            .map(_ => none[Hashed[GlobalIncrementalSnapshot]])
        }

      def pullGlobalSnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        pullGlobalSnapshot(l0GlobalSnapshotClient.get(ordinal)).handleErrorWith { e =>
          logger
            .warn(e)(s"Failure pulling single snapshot with ordinal=$ordinal")
            .map(_ => none[Hashed[GlobalIncrementalSnapshot]])
        }

      private def pullGlobalSnapshot(
        peerResponse: PeerResponse.PeerResponse[F, Signed[GlobalIncrementalSnapshot]]
      ): F[Option[Hashed[GlobalIncrementalSnapshot]]] =
        globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
          peerResponse(l0Peer)
            .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
            .map(_.some)
        }

      def pullGlobalSnapshots: F[Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), List[Hashed[GlobalIncrementalSnapshot]]]] =
        lastGlobalSnapshotStorage.getOrdinal.flatMap {
          _.fold {
            pullLatestSnapshot.map(_.asLeft[List[Hashed[GlobalIncrementalSnapshot]]])
          } { lastStoredOrdinal =>
            def pulled = globalL0ClusterStorage.getRandomPeer.flatMap { l0Peer =>
              l0GlobalSnapshotClient.getLatestOrdinal
                .run(l0Peer)
                .map { lastOrdinal =>
                  val nextOrdinal = lastStoredOrdinal.next
                  val lastOrdinalCap = lastOrdinal.value.value
                    .min(singlePullLimit.map(nextOrdinal.value.value + _.value).getOrElse(lastOrdinal.value.value))

                  nextOrdinal.value.value to lastOrdinalCap
                }
                .map(_.toList.map(o => SnapshotOrdinal(NonNegLong.unsafeFrom(o))))
                .flatMap { ordinals =>
                  (ordinals, List.empty[Hashed[GlobalIncrementalSnapshot]]).tailRecM {
                    case (ordinal :: nextOrdinals, snapshots) =>
                      l0GlobalSnapshotClient
                        .get(ordinal)(l0Peer)
                        .flatMap(_.toHashedWithSignatureCheck.flatMap(_.liftTo[F]))
                        .map(s => (nextOrdinals, snapshots :+ s).asLeft[List[Hashed[GlobalIncrementalSnapshot]]])
                        .handleErrorWith { e =>
                          logger
                            .warn(e)(s"Failure pulling snapshot with ordinal=$ordinal")
                            .map(_ => snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalIncrementalSnapshot]])])
                        }

                    case (Nil, snapshots) =>
                      Applicative[F].pure(snapshots.asRight[(List[SnapshotOrdinal], List[Hashed[GlobalIncrementalSnapshot]])])
                  }
                }
            }

            pulled.map(_.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
          }
        }.handleErrorWith { e =>
          logger.warn(e)(s"Failure pulling global snapshots!") >>
            Applicative[F].pure(
              List.empty[Hashed[GlobalIncrementalSnapshot]].asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
            )
        }
    }
}