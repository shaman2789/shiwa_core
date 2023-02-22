package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.sdk.infrastructure.consensus.ConsensusManager
import org.tessellation.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.currency.schema.currency.CurrencySnapshotInfo

trait GenesisService[F[_]] {
  def accept(genesis: CurrencySnapshot): F[Unit]
}

object GenesisService {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    collateral: Collateral[F],
    lastSignedBinaryHashStorage: LastSignedBinaryHashStorage[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0Peer: L0Peer,
    nodeId: PeerId,
    consensusManager: ConsensusManager[F, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext]
  ): GenesisService[F] = new GenesisService[F] {
    private val logger = Slf4jLogger.getLogger

    override def accept(genesis: CurrencySnapshot): F[Unit] = for {
      hashedGenesis <- genesis.sign(keyPair).flatMap(_.toHashed[F])
      firstIncrementalSnapshot <- CurrencySnapshot.mkFirstIncrementalSnapshot[F](hashedGenesis)
      signedFirstIncrementalSnapshot <- firstIncrementalSnapshot.sign(keyPair)
      _ <- snapshotStorage.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info)
      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)
      signedBinary <- stateChannelSnapshotService.createGenesisBinary(hashedGenesis.signed)
      signedBinaryHash <- signedBinary.hashF
      _ <- stateChannelSnapshotClient.send(signedBinary)(globalL0Peer)
      signedIncrementalBinary <- stateChannelSnapshotService.createBinary(signedFirstIncrementalSnapshot)
      signedIncrementalBinaryHash <- signedIncrementalBinary.hashF
      _ <- stateChannelSnapshotClient.send(signedIncrementalBinary)(globalL0Peer)
      _ <- lastSignedBinaryHashStorage.set(signedIncrementalBinaryHash)
      _ <- consensusManager.startFacilitatingAfter(
        signedFirstIncrementalSnapshot.ordinal,
        signedFirstIncrementalSnapshot,
        hashedGenesis.info
      )
      _ <- logger.info(s"Genesis binary ${signedBinaryHash.show} and ${signedIncrementalBinaryHash.show} accepted and sent to Global L0")
    } yield ()
  }
}
