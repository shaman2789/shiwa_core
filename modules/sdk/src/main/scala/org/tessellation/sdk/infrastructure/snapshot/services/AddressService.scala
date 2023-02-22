package org.tessellation.sdk.infrastructure.snapshot.services

import cats.Functor
import cats.syntax.functor._

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.domain.snapshot.services.AddressService
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.security.signature.Signed

import io.estatico.newtype.ops._

object AddressService {
  def make[F[_]: Functor, S <: Snapshot[_, _]](snapshotStorage: SnapshotStorage[F, S]): AddressService[F, S] = new AddressService[F, S] {
    private def getBalance(snapshot: Signed[S], address: Address): (Balance, SnapshotOrdinal) = {
      val balance = snapshot.value.info.balances.getOrElse(address, Balance.empty)
      val ordinal = snapshot.value.ordinal

      (balance, ordinal)
    }

    def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
      snapshotStorage.head.map {
        _.map(getBalance(_, address))
      }

    def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]] =
      snapshotStorage.get(ordinal).map {
        _.map(getBalance(_, address))
      }

    private def getTotalSupply(snapshot: Signed[S]): (BigInt, SnapshotOrdinal) = {
      val empty = BigInt(Balance.empty.coerce.value)
      val supply = snapshot.value.info.balances.values
        .foldLeft(empty) { (acc, b) =>
          acc + BigInt(b.coerce.value)
        }
      val ordinal = snapshot.value.ordinal

      (supply, ordinal)
    }

    def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]] =
      snapshotStorage.head.map {
        _.map(getTotalSupply)
      }

    def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]] =
      snapshotStorage.get(ordinal).map {
        _.map(getTotalSupply)
      }

    private def getWalletCount(snapshot: Signed[S]): (Int, SnapshotOrdinal) = {
      val wallets = snapshot.value.info.balances.size
      val ordinal = snapshot.value.ordinal

      (wallets, ordinal)
    }

    def getWalletCount: F[Option[(Int, SnapshotOrdinal)]] =
      snapshotStorage.head.map {
        _.map(getWalletCount)
      }

    def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]] =
      snapshotStorage.get(ordinal).map {
        _.map(getWalletCount)
      }
  }
}
