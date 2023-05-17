package org.shiwa.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.shiwa.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, SnapshotFee}
import org.shiwa.ext.cats.syntax.next._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema._
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.{Amount, Balance}
import org.shiwa.schema.block.SHIBlock
import org.shiwa.schema.epoch.EpochProgress
import org.shiwa.schema.transaction.SHITransaction
import org.shiwa.sdk.config.AppEnvironment
import org.shiwa.sdk.domain.block.processing._
import org.shiwa.sdk.domain.rewards.Rewards
import org.shiwa.sdk.domain.snapshot.storage.SnapshotStorage
import org.shiwa.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger}
import org.shiwa.sdk.infrastructure.metrics.Metrics
import org.shiwa.sdk.infrastructure.snapshot.{GlobalSnapshotAcceptanceManager, GlobalSnapshotStateChannelEventsProcessor}
import org.shiwa.sdk.sdkKryoRegistrar
import org.shiwa.security.hash.Hash
import org.shiwa.security.key.ops.PublicKeyOps
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.Signed.forAsyncKryo
import org.shiwa.security.{KeyPairGenerator, SecurityProvider}
import org.shiwa.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.shiwa.syntax.sortedCollection._

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](sdkKryoRegistrar).flatMap { ks =>
        SecurityProvider.forAsync[IO].flatMap { sp =>
          Metrics.forAsync[IO](Seq.empty).map((supervisor, ks, sp, _))
        }
      }
    }

  val gss: SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???
      override def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

    }

  val bam: BlockAcceptanceManager[IO, SHITransaction, SHIBlock] = new BlockAcceptanceManager[IO, SHITransaction, SHIBlock] {

    override def acceptBlocksIteratively(
      blocks: List[Signed[SHIBlock]],
      context: BlockAcceptanceContext[IO]
    ): IO[BlockAcceptanceResult[SHIBlock]] =
      BlockAcceptanceResult[SHIBlock](
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[SHIBlock],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent]
    ): IO[
      (
        SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set[StateChannelEvent]
      )
    ] = IO(
      (events.groupByNel(_.address).view.mapValues(_.map(_.snapshotBinary)).toSortedMap, SortedMap.empty, Set.empty)
    )
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[F, SHITransaction, SHIBlock, GlobalSnapshotStateProof, GlobalIncrementalSnapshot] =
    (
      artifact: Signed[GlobalSnapshotArtifact],
      balances: SortedMap[Address, Balance],
      transactions: SortedSet[Signed[SHITransaction]],
      trigger: ConsensusTrigger
    ) => IO(SortedSet.empty)

  def mkGlobalSnapshotConsensusFunctions()(implicit ks: KryoSerializer[IO], sp: SecurityProvider[IO], m: Metrics[IO]) = {
    val snapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make[IO](bam, scProcessor, collateral)
    GlobalSnapshotConsensusFunctions.make[IO](gss, snapshotAcceptanceManager, collateral, rewards, env)
  }

  val env: AppEnvironment = AppEnvironment.Testnet

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = mkGlobalSnapshotConsensusFunctions()

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
            mkStateChannelEvent().flatMap { scEvent =>
              gscf
                .createProposalArtifact(
                  SnapshotOrdinal.MinValue,
                  signedLastArtifact,
                  signedGenesis.value.info,
                  EventTrigger,
                  Set(scEvent.asLeft[SHIEvent])
                )
                .flatMap {
                  case (artifact, _, _) =>
                    gscf.validateArtifact(signedLastArtifact, signedGenesis.value.info, EventTrigger, artifact).map { result =>
                      expect.same(result.isRight, true) && expect
                        .same(
                          result.map(_._1.stateChannelSnapshots(scEvent.address)),
                          Right(NonEmptyList.one(scEvent.snapshotBinary))
                        )
                    }
                }
            }
          }
        }
      }
    }
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = mkGlobalSnapshotConsensusFunctions()

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value).flatMap { lastArtifact =>
          Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair).flatMap { signedLastArtifact =>
            mkStateChannelEvent().flatMap { scEvent =>
              gscf
                .createProposalArtifact(
                  SnapshotOrdinal.MinValue,
                  signedLastArtifact,
                  signedGenesis.value.info,
                  EventTrigger,
                  Set(scEvent.asLeft[SHIEvent])
                )
                .flatMap { proposalArtifact =>
                  gscf
                    .validateArtifact(
                      signedLastArtifact,
                      signedGenesis.value.info,
                      EventTrigger,
                      proposalArtifact._1.copy(ordinal = proposalArtifact._1.ordinal.next)
                    )
                    .map { result =>
                      expect.same(result.isLeft, true)
                    }
                }
            }
          }
        }
      }
    }
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
