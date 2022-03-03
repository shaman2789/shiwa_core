package org.tessellation.sdk.infrastructure.consensus

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Semaphore
import cats.kernel.Next
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Eq, Show}

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.Ordinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

import io.chrisdavenport.mapref.MapRef
import monocle.syntax.all._

trait ConsensusStorage[F[_], Event, Key, Artifact] {

  trait ModifyStateFn[B]
      extends (Option[ConsensusState[Key, Artifact]] => F[Option[(Option[ConsensusState[Key, Artifact]], B)]])

  def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]]

  def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]]

  def findEvent(predicate: Event => Boolean): F[Option[Event]]

  def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit]

  def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit]

  def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]]

  def getUpperBound: F[Bound]

  def getResources(key: Key): F[Option[ConsensusResources[Artifact]]]

  def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]]

  def addFacility(peerId: PeerId, key: Key, bound: Bound): F[ConsensusResources[Artifact]]

  def addProposal(peerId: PeerId, key: Key, hash: Hash): F[ConsensusResources[Artifact]]

  def addSignature(peerId: PeerId, key: Key, signature: Signature): F[ConsensusResources[Artifact]]

  def setLastKeyAndArtifact(value: Option[(Key, Artifact)]): F[Unit]

  def getLastKeyAndArtifact: F[Option[(Key, Artifact)]]

  def tryUpdateLastKeyAndArtifactWithCleanup(oldValue: (Key, Artifact), newValue: (Key, Artifact)): F[Boolean]

}

object ConsensusStorage {

  def make[F[_]: Async: KryoSerializer, Event, Key: Show: Next: Eq, Artifact <: AnyRef: Show: Eq](
    lastKeyAndArtifact: Option[(Key, Artifact)] = none[(Key, Artifact)]
  ): F[ConsensusStorage[F, Event, Key, Artifact]] =
    for {
      stateUpdateSemaphore <- Semaphore[F](1)
      lastKeyAndArtifactR <- Ref.of(lastKeyAndArtifact)
      eventsR <- MapRef.ofConcurrentHashMap[F, PeerId, List[(Ordinal, Event)]]()
      statesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusState[Key, Artifact]]()
      resourcesR <- MapRef.ofConcurrentHashMap[F, Key, ConsensusResources[Artifact]]()
    } yield make(stateUpdateSemaphore, lastKeyAndArtifactR, eventsR, statesR, resourcesR)

  def make[F[_]: Async: KryoSerializer, Event, Key: Show: Next: Eq, Artifact <: AnyRef: Show: Eq](
    stateUpdateSemaphore: Semaphore[F],
    lastKeyAndArtifactR: Ref[F, Option[(Key, Artifact)]],
    eventsR: MapRef[F, PeerId, Option[List[(Ordinal, Event)]]],
    statesR: MapRef[F, Key, Option[ConsensusState[Key, Artifact]]],
    resourcesR: MapRef[F, Key, Option[ConsensusResources[Artifact]]]
  ): ConsensusStorage[F, Event, Key, Artifact] =
    new ConsensusStorage[F, Event, Key, Artifact] {

      def getState(key: Key): F[Option[ConsensusState[Key, Artifact]]] =
        statesR(key).get

      def getResources(key: Key): F[Option[ConsensusResources[Artifact]]] =
        resourcesR(key).get

      def condModifyState[B](key: Key)(modifyStateFn: ModifyStateFn[B]): F[Option[B]] =
        stateUpdateSemaphore.permit.use { _ =>
          for {
            (maybeState, setter) <- statesR(key).access
            maybeResult <- modifyStateFn(maybeState)

            maybeB <- maybeResult.traverse {
              case (maybeState, b) =>
                setter(maybeState)
                  .ifM(
                    b.pure[F],
                    new Throwable(
                      "Failed consensus state update, all consensus state updates should be sequenced with a semaphore"
                    ).raiseError[F, B]
                  )
            }
          } yield maybeB
        }

      def setLastKeyAndArtifact(value: Option[(Key, Artifact)]): F[Unit] = lastKeyAndArtifactR.set(value)

      def getLastKeyAndArtifact: F[Option[(Key, Artifact)]] = lastKeyAndArtifactR.get

      def tryUpdateLastKeyAndArtifactWithCleanup(oldValue: (Key, Artifact), newValue: (Key, Artifact)): F[Boolean] =
        lastKeyAndArtifactR.modify { maybeValue =>
          if (maybeValue === oldValue.some)
            (newValue.some, true)
          else
            (maybeValue, false)
        }.flatTap(_ => cleanupStateAndResource(oldValue._1))

      private def cleanupStateAndResource(key: Key): F[Unit] =
        condModifyState[Unit](key) { _ =>
          (none[ConsensusState[Key, Artifact]], ()).some.pure[F]
        }.void

      def findEvent(predicate: Event => Boolean): F[Option[Event]] =
        for {
          peerIds <- eventsR.keys
          maybeFoundEvent <- peerIds.foldM(none[Event]) { (acc, peerId) =>
            acc match {
              case Some(foundEvent) => foundEvent.some.pure[F]
              case None =>
                eventsR(peerId).get.map {
                  _.flatMap(events => events.map(_._2).find(predicate))
                }
            }
          }
        } yield maybeFoundEvent

      def addEvent(peerId: PeerId, peerEvent: (Ordinal, Event)): F[Unit] =
        addEvents(Map(peerId -> List(peerEvent)))

      def addEvents(events: Map[PeerId, List[(Ordinal, Event)]]): F[Unit] =
        events.toList.traverse {
          case (peerId, peerEvents) =>
            eventsR(peerId).update { maybePeerEvents =>
              (peerEvents ++ maybePeerEvents
                .getOrElse(List.empty)).some
            }
        }.void

      def pullEvents(upperBound: Bound): F[Map[PeerId, List[(Ordinal, Event)]]] =
        upperBound.toList.traverse {
          case (peerId, peerBound) =>
            eventsR(peerId).modify { maybePeerEvents =>
              maybePeerEvents.traverse { peerEvents =>
                peerEvents.partitionMap {
                  case oe @ (eventOrdinal, _) =>
                    Either.cond(eventOrdinal > peerBound, oe, oe)
                }
              }.swap
            }.map((peerId, _))
        }.map(_.toMap)

      def getUpperBound: F[Bound] =
        for {
          peerIds <- eventsR.keys
          bound <- peerIds.traverseFilter { peerId =>
            eventsR(peerId).get.map { maybePeerEvents =>
              maybePeerEvents.flatMap { peerEvents =>
                peerEvents.map(_._1).maximumOption.map((peerId, _))
              }
            }
          }
        } yield bound.toMap

      def addFacility(peerId: PeerId, key: Key, bound: Bound): F[ConsensusResources[Artifact]] =
        updatePeerDeclaration(key, peerId) { peerDeclaration =>
          peerDeclaration.focus(_.upperBound).modify(_.orElse(bound.some))
        }

      def addProposal(peerId: PeerId, key: Key, hash: Hash): F[ConsensusResources[Artifact]] =
        updatePeerDeclaration(key, peerId) { peerDeclaration =>
          peerDeclaration.focus(_.proposal).modify(_.orElse(hash.some))
        }

      def addSignature(peerId: PeerId, key: Key, signature: Signature): F[ConsensusResources[Artifact]] =
        updatePeerDeclaration(key, peerId) { peerDeclaration =>
          peerDeclaration.focus(_.signature).modify(_.orElse(signature.some))
        }

      def addArtifact(key: Key, artifact: Artifact): F[ConsensusResources[Artifact]] =
        artifact.hashF >>= { hash =>
          resourcesR(key).updateAndGet { maybeResource =>
            maybeResource
              .getOrElse(ConsensusResources.empty)
              .focus(_.artifacts)
              .at(hash)
              .replace(artifact.some)
              .some
          }.flatMap(_.liftTo[F](new RuntimeException("Should never happen")))
        }

      private def updatePeerDeclaration(key: Key, peerId: PeerId)(f: PeerDeclaration => PeerDeclaration) =
        resourcesR(key).updateAndGet { maybeResource =>
          maybeResource
            .getOrElse(ConsensusResources.empty)
            .focus(_.peerDeclarations)
            .at(peerId)
            .modify { maybePeerDeclaration =>
              f(maybePeerDeclaration.getOrElse(PeerDeclaration.empty)).some
            }
            .some
        }.flatMap(_.liftTo[F](new RuntimeException("Should never happen")))

    }
}