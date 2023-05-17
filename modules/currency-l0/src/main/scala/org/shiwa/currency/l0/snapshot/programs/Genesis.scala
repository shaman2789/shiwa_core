package org.shiwa.currency.l0.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.shiwa.currency.l0.snapshot.services.StateChannelSnapshotService
import org.shiwa.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.shiwa.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.shiwa.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.peer.{L0Peer, PeerId}
import org.shiwa.sdk.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.shiwa.sdk.domain.genesis.{Loader => GenesisLoader}
import org.shiwa.sdk.domain.snapshot.storage.SnapshotStorage
import org.shiwa.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.shiwa.sdk.infrastructure.consensus.ConsensusManager
import org.shiwa.security.SecurityProvider

import fs2.io.file.Path
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Genesis[F[_]] {
  def accept(path: Path): F[Unit]
  def accept(genesis: CurrencySnapshot): F[Unit]
}

object Genesis {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    collateral: Collateral[F],
    lastSignedBinaryHashStorage: LastSignedBinaryHashStorage[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0Peer: L0Peer,
    nodeId: PeerId,
    consensusManager: ConsensusManager[F, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext],
    genesisLoader: GenesisLoader[F]
  ): Genesis[F] = new Genesis[F] {
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

      _ <- lastSignedBinaryHashStorage.set(signedBinaryHash)

      signedIncrementalBinary <- stateChannelSnapshotService.createBinary(signedFirstIncrementalSnapshot)
      signedIncrementalBinaryHash <- signedIncrementalBinary.hashF
      _ <- stateChannelSnapshotClient.send(signedIncrementalBinary)(globalL0Peer)

      _ <- lastSignedBinaryHashStorage.set(signedIncrementalBinaryHash)

      _ <- consensusManager.startFacilitatingAfterRollback(
        signedFirstIncrementalSnapshot.ordinal,
        signedFirstIncrementalSnapshot,
        hashedGenesis.info
      )
      _ <- logger.info(s"Genesis binary ${signedBinaryHash.show} and ${signedIncrementalBinaryHash.show} accepted and sent to Global L0")
    } yield ()

    def accept(path: Path): F[Unit] =
      genesisLoader
        .load(path)
        .map(_.map(a => (a.address, a.balance)).toMap)
        .map(CurrencySnapshot.mkGenesis)
        .flatMap(accept)
  }
}
