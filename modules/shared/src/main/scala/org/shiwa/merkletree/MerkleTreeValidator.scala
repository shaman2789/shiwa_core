package org.shiwa.merkletree

import cats.MonadThrow
import cats.data.Validated
import cats.kernel.Eq
import cats.syntax.eq._
import cats.syntax.functor._

import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.snapshot.{IncrementalSnapshot, SnapshotInfo, StateProof}
import org.shiwa.security.{Hashed, hash}

import derevo.cats.{eqv, show}
import derevo.derive

object StateProofValidator {

  def validate[F[_]: MonadThrow: KryoSerializer, P <: StateProof: Eq, A <: IncrementalSnapshot[_, _, P]](
    snapshot: Hashed[A],
    si: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] = si.stateProof.map(validate(snapshot, _))

  def validate[P <: StateProof: Eq, A <: IncrementalSnapshot[_, _, P]](
    snapshot: Hashed[A],
    stateProof: P
  ): Validated[StateBroken, Unit] =
    Validated.cond(stateProof === snapshot.signed.value.stateProof, (), StateBroken(snapshot.ordinal, snapshot.hash))

  @derive(eqv, show)
  case class StateBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: hash.Hash)

  type StateValidationErrorOrUnit = Validated[StateBroken, Unit]
}
