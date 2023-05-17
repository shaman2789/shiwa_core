package org.shiwa.domain.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.semigroup._
import cats.syntax.show._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import org.shiwa.domain.snapshot.storages.SnapshotDownloadStorage
import org.shiwa.ext.cats.kernel.PartialPrevious
import org.shiwa.ext.cats.syntax.next.catsSyntaxNext
import org.shiwa.http.p2p.P2PClient
import org.shiwa.infrastructure.snapshot.GlobalSnapshotContext
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.peer.Peer
import org.shiwa.sdk.domain.cluster.storage.ClusterStorage
import org.shiwa.sdk.domain.node.NodeStorage
import org.shiwa.sdk.domain.snapshot.PeerSelect
import org.shiwa.sdk.domain.snapshot.programs.Download
import org.shiwa.sdk.infrastructure.snapshot.{GlobalSnapshotContextFunctions, SnapshotConsensus}
import org.shiwa.security.Hashed
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies._
import retry._

object Download {
  def make[F[_]: Async: KryoSerializer: Random](
    snapshotStorage: SnapshotDownloadStorage[F],
    p2pClient: P2PClient[F],
    clusterStorage: ClusterStorage[F],
    lastFullGlobalSnapshotOrdinal: SnapshotOrdinal,
    globalSnapshotContextFns: GlobalSnapshotContextFunctions[F],
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, _, _, GlobalIncrementalSnapshot, GlobalSnapshotContext, _],
    peerSelect: PeerSelect[F]
  ): Download[F] = new Download[F] {

    val logger = Slf4jLogger.getLogger[F]

    val minBatchSizeToStartObserving: Long = 1L
    val observationOffset = NonNegLong(4L)
    val fetchSnapshotDelayBetweenTrials = 10.seconds

    type DownloadResult = (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)
    type ObservationLimit = SnapshotOrdinal

    def download: F[Unit] =
      nodeStorage
        .tryModifyState(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)(start)
        .flatMap(observe)
        .flatMap { result =>
          val ((snapshot, context), observationLimit) = result

          consensus.manager.startFacilitatingAfterDownload(observationLimit, snapshot, context)
        }

    def start: F[DownloadResult] = {
      def latestMetadata = peerSelect.select.flatMap {
        p2pClient.globalSnapshot.getLatestMetadata.run(_)
      }

      def go(startingPoint: SnapshotOrdinal, result: Option[DownloadResult]): F[DownloadResult] =
        latestMetadata.flatTap { metadata =>
          logger.info(s"Download for startingPoint=${startingPoint}. Latest metadata=${metadata.show}")
        }.flatMap { metadata =>
          val batchSize = metadata.ordinal.value.value - startingPoint.value.value

          if (batchSize <= minBatchSizeToStartObserving && startingPoint =!= lastFullGlobalSnapshotOrdinal) {
            result.map(_.pure[F]).getOrElse(UnexpectedState.raiseError[F, DownloadResult])
          } else
            download(metadata.hash, metadata.ordinal).flatMap { case (snapshot, context) => go(snapshot.ordinal, (snapshot, context).some) }
        }

      go(lastFullGlobalSnapshotOrdinal, none[DownloadResult])
    }

    def observe(result: DownloadResult): F[(DownloadResult, ObservationLimit)] = {
      val (lastSnapshot, _) = result

      val observationLimit = SnapshotOrdinal(lastSnapshot.ordinal.value |+| observationOffset)

      def go(result: DownloadResult): F[DownloadResult] = {
        val (lastSnapshot, _) = result

        if (lastSnapshot.ordinal === observationLimit) {
          result.pure[F]
        } else fetchNextSnapshot(result) >>= go
      }

      consensus.manager.registerForConsensus(observationLimit) >>
        go(result).map((_, observationLimit))
    }

    def fetchNextSnapshot(result: DownloadResult): F[DownloadResult] = {
      def retryPolicy = constantDelay(fetchSnapshotDelayBetweenTrials)

      def isWorthRetrying(err: Throwable): F[Boolean] = err match {
        case CannotFetchSnapshot | InvalidChain => true.pure[F]
        case _                                  => false.pure[F]
      }

      retryingOnSomeErrors(retryPolicy, isWorthRetrying, retry.noop[F, Throwable]) {
        val (lastSnapshot, lastContext) = result

        fetchSnapshot(none, lastSnapshot.ordinal.next).flatMap { snapshot =>
          globalSnapshotContextFns
            .createContext(lastContext, lastSnapshot.value, snapshot)
            .handleErrorWith(_ => InvalidChain.raiseError[F, GlobalSnapshotContext])
            .flatTap { _ =>
              snapshotStorage.writePersisted(snapshot)
            }
            .map((snapshot, _))
        }
      }
    }

    def download(hash: Hash, ordinal: SnapshotOrdinal): F[DownloadResult] = {

      def go(tmpMap: Map[SnapshotOrdinal, Hash], stepHash: Hash, stepOrdinal: SnapshotOrdinal): F[DownloadResult] =
        isSnapshotPersistedOrReachedGenesis(stepHash, stepOrdinal).ifM(
          validateChain(tmpMap, ordinal),
          snapshotStorage
            .readTmp(stepOrdinal)
            .flatMap {
              case Some(snapshot) =>
                snapshot.toHashed[F].map { hashed =>
                  if (hashed.hash === stepHash) snapshot.some else none[Signed[GlobalIncrementalSnapshot]]
                }
              case None => none[Signed[GlobalIncrementalSnapshot]].pure[F]
            }
            .flatMap {
              _.map(_.toHashed[F])
                .getOrElse(fetchSnapshot(stepHash.some, stepOrdinal).flatMap { snapshot =>
                  snapshotStorage.writeTmp(snapshot).flatMap(_ => snapshot.toHashed[F])
                })
                .flatMap { hashed =>
                  def updated = tmpMap + (hashed.ordinal -> hashed.hash)

                  PartialPrevious[SnapshotOrdinal]
                    .partialPrevious(hashed.ordinal)
                    .map {
                      go(updated, hashed.lastSnapshotHash, _)
                    }
                    .getOrElse(HashAndOrdinalMismatch.raiseError[F, DownloadResult])
                }

            }
        )

      go(Map.empty, hash, ordinal)
    }

    def isSnapshotPersistedOrReachedGenesis(hash: Hash, ordinal: SnapshotOrdinal): F[Boolean] = {
      def isSnapshotPersisted = snapshotStorage.isPersisted(hash)
      def didReachGenesis = ordinal === lastFullGlobalSnapshotOrdinal

      if (!didReachGenesis) {
        isSnapshotPersisted
      } else true.pure[F]
    }

    def validateChain(
      tmpMap: Map[SnapshotOrdinal, Hash],
      endingOrdinal: SnapshotOrdinal
    ): F[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)] = {

      type Agg = DownloadResult

      def go(lastSnapshot: Signed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): F[Agg] = {
        val nextOrdinal = lastSnapshot.ordinal.next

        def readSnapshot = tmpMap
          .get(nextOrdinal)
          .as(snapshotStorage.readTmp(nextOrdinal))
          .getOrElse(snapshotStorage.readPersisted(nextOrdinal))

        if (lastSnapshot.ordinal.value >= endingOrdinal.value) {
          (lastSnapshot, context).pure[F]
        } else
          readSnapshot.flatMap {
            case Some(snapshot) =>
              globalSnapshotContextFns.createContext(context, lastSnapshot.value, snapshot).flatMap { newContext =>
                Applicative[F].whenA(tmpMap.contains(snapshot.ordinal)) {
                  snapshotStorage.readPersisted(snapshot.ordinal).flatMap {
                    _.map(
                      _.toHashed[F]
                        .map(_.hash)
                        .flatMap(snapshotStorage.movePersistedToTmp(_))
                    ).getOrElse(Applicative[F].unit)
                  } >>
                    snapshotStorage
                      .moveTmpToPersisted(snapshot)
                } >>
                  go(snapshot, newContext)
              }
            case None => InvalidChain.raiseError[F, Agg]
          }
      }

      getGenesisSnapshot(tmpMap).flatMap {
        case (fullSnapshot, incrementalSnapshot) =>
          go(incrementalSnapshot, GlobalSnapshotInfoV1.toGlobalSnapshotInfo(fullSnapshot.info))
      }
    }

    def getGenesisSnapshot(
      tmpMap: Map[SnapshotOrdinal, Hash]
    ): F[(GlobalSnapshot, Signed[GlobalIncrementalSnapshot])] =
      snapshotStorage
        .readGenesis(lastFullGlobalSnapshotOrdinal)
        .flatMap {
          _.map(_.pure[F]).getOrElse {
            fetchGenesis(lastFullGlobalSnapshotOrdinal)
          }
        }
        .flatMap { genesis =>
          val incrementalGenesisOrdinal = genesis.ordinal.next

          tmpMap
            .get(incrementalGenesisOrdinal)
            .as(snapshotStorage.readTmp(incrementalGenesisOrdinal))
            .getOrElse(snapshotStorage.readPersisted(incrementalGenesisOrdinal))
            .flatMap {
              case Some(snapshot) => (genesis.value, snapshot).pure[F]
              case None           => fetchSnapshot(none[Hash], incrementalGenesisOrdinal).map((genesis, _))
            }
        }

    def fetchSnapshot(hash: Option[Hash], ordinal: SnapshotOrdinal): F[Signed[GlobalIncrementalSnapshot]] =
      clusterStorage.getPeers
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Downloading snapshot hash=${hash.show}, ordinal=${ordinal.show}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalIncrementalSnapshot]
          type Result = Option[Success]
          type Agg = (List[Peer], Result)

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .get(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[GlobalIncrementalSnapshot]])
                .map {
                  case Some(snapshot) if hash.forall(_ === snapshot.hash) => snapshot.signed.some.asRight[Agg]
                  case _                                                  => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchSnapshot.raiseError[F, Signed[GlobalIncrementalSnapshot]]
        }

    def fetchGenesis(ordinal: SnapshotOrdinal): F[Signed[GlobalSnapshot]] =
      clusterStorage.getPeers
        .map(_.toList)
        .flatMap(Random[F].shuffleList)
        .flatTap { _ =>
          logger.info(s"Downloading genesis snapshot ordinal=${ordinal}")
        }
        .flatMap { peers =>
          type Success = Signed[GlobalSnapshot]
          type Agg = (List[Peer], Option[Signed[GlobalSnapshot]])
          type Result = Option[Success]

          (peers, none[Success]).tailRecM[F, Result] {
            case (Nil, snapshot) => snapshot.asRight[Agg].pure[F]
            case (peer :: tail, _) =>
              p2pClient.globalSnapshot
                .getFull(ordinal)
                .run(peer)
                .flatMap(_.toHashed[F])
                .map(_.some)
                .handleError(_ => none[Hashed[GlobalSnapshot]])
                .map {
                  case Some(snapshot) => snapshot.signed.some.asRight[Agg]
                  case _              => (tail, none[Success]).asLeft[Result]
                }
          }
        }
        .flatMap {
          case Some(snapshot) => snapshot.pure[F]
          case _              => CannotFetchGenesisSnapshot.raiseError[F, Signed[GlobalSnapshot]]
        }
  }

  case object HashAndOrdinalMismatch extends NoStackTrace
  case object CannotFetchSnapshot extends NoStackTrace
  case object CannotFetchGenesisSnapshot extends NoStackTrace
  case object InvalidChain extends NoStackTrace
  case object UnexpectedState extends NoStackTrace
}