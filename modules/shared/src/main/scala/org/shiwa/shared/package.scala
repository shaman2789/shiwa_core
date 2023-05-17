package org.shiwa

import java.security.Signature

import cats.data.NonEmptyList

import org.shiwa.currency.schema.currency._
import org.shiwa.ext.kryo._
import org.shiwa.merkletree.{MerkleRoot, Proof, ProofEntry}
import org.shiwa.schema._
import org.shiwa.schema.address.{Address, AddressCache}
import org.shiwa.schema.block.{SHIBlock, Tips}
import org.shiwa.schema.gossip._
import org.shiwa.schema.node.NodeState
import org.shiwa.schema.peer.SignRequest
import org.shiwa.schema.snapshot.StateProof
import org.shiwa.schema.transaction._
import org.shiwa.schema.trust.PublicTrust
import org.shiwa.security.signature.Signed
import org.shiwa.security.signature.Signed.SignedOrdering
import org.shiwa.security.signature.signature.SignatureProof
import org.shiwa.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Greater

package object shared {

  type SharedKryoRegistrationIdRange = Greater[100]

  type SharedKryoRegistrationId = KryoRegistrationId[SharedKryoRegistrationIdRange]

  val sharedKryoRegistrar: Map[Class[_], SharedKryoRegistrationId] = Map(
    classOf[SignatureProof] -> 301,
    SignatureProof.OrderingInstance.getClass -> 302,
    classOf[Signature] -> 303,
    classOf[SignRequest] -> 304,
    classOf[NonEmptyList[_]] -> 305,
    classOf[Signed[_]] -> 306,
    classOf[AddressCache] -> 307,
    classOf[PeerRumorRaw] -> 308,
    NodeState.Initial.getClass -> 313,
    NodeState.ReadyToJoin.getClass -> 314,
    NodeState.LoadingGenesis.getClass -> 315,
    NodeState.GenesisReady.getClass -> 316,
    NodeState.RollbackInProgress.getClass -> 317,
    NodeState.RollbackDone.getClass -> 318,
    NodeState.StartingSession.getClass -> 319,
    NodeState.SessionStarted.getClass -> 320,
    NodeState.WaitingForDownload.getClass -> 321,
    NodeState.DownloadInProgress.getClass -> 322,
    NodeState.Ready.getClass -> 323,
    NodeState.Leaving.getClass -> 324,
    NodeState.Offline.getClass -> 325,
    classOf[PublicTrust] -> 326,
    classOf[Ordinal] -> 327,
    classOf[CommonRumorRaw] -> 328,
    classOf[SHITransaction] -> 329,
    SHITransaction.OrderingInstance.getClass -> 330,
    classOf[TransactionReference] -> 331,
    classOf[Refined[_, _]] -> 332,
    classOf[RewardTransaction] -> 333,
    RewardTransaction.OrderingInstance.getClass -> 334,
    classOf[SignedOrdering[_]] -> 335,
    Address.OrderingInstance.getClass -> 336,
    NodeState.Observing.getClass -> 337,
    classOf[GlobalSnapshot] -> 600,
    classOf[StateChannelSnapshotBinary] -> 601,
    classOf[SnapshotOrdinal] -> 602,
    classOf[SHIBlock] -> 603,
    SHIBlock.OrderingInstance.getClass -> 604,
    classOf[BlockReference] -> 605,
    classOf[GlobalSnapshotInfoV1] -> 606,
    classOf[SnapshotTips] -> 607,
    classOf[ActiveTip] -> 608,
    ActiveTip.OrderingInstance.getClass -> 609,
    classOf[BlockAsActiveTip[_]] -> 610,
    SHIBlock.OrderingInstanceAsActiveTip.getClass -> 611,
    classOf[DeprecatedTip] -> 612,
    DeprecatedTip.OrderingInstance.getClass -> 613,
    classOf[Tips] -> 614,
    classOf[GlobalIncrementalSnapshot] -> 615,
    classOf[MerkleRoot] -> 616,
    classOf[GlobalSnapshotInfo] -> 617,
    classOf[CurrencyTransaction] -> 618,
    classOf[CurrencyBlock] -> 619,
    classOf[CurrencySnapshotInfo] -> 620,
    classOf[CurrencySnapshot] -> 621,
    classOf[CurrencyIncrementalSnapshot] -> 622,
    CurrencyBlock.OrderingInstanceAsActiveTip.getClass -> 623,
    CurrencyTransaction.OrderingInstance.getClass -> 624,
    CurrencyBlock.OrderingInstance.getClass -> 625,
    classOf[Proof] -> 626,
    classOf[ProofEntry] -> 627,
    classOf[GlobalSnapshotStateProof] -> 628,
    classOf[CurrencySnapshotStateProof] -> 629,
    classOf[StateProof] -> 630
  )

}