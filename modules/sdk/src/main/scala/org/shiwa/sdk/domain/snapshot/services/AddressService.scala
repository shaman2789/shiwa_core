package org.shiwa.sdk.domain.snapshot.services

import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.address.Address
import org.shiwa.schema.balance.Balance
import org.shiwa.schema.snapshot.Snapshot

trait AddressService[F[_], S <: Snapshot[_, _]] {
  def getBalance(address: Address): F[Option[(Balance, SnapshotOrdinal)]]
  def getBalance(ordinal: SnapshotOrdinal, address: Address): F[Option[(Balance, SnapshotOrdinal)]]
  def getTotalSupply: F[Option[(BigInt, SnapshotOrdinal)]]

  def getTotalSupply(ordinal: SnapshotOrdinal): F[Option[(BigInt, SnapshotOrdinal)]]
  def getWalletCount: F[Option[(Int, SnapshotOrdinal)]]
  def getWalletCount(ordinal: SnapshotOrdinal): F[Option[(Int, SnapshotOrdinal)]]
}
