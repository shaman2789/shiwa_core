package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.signature.Signed
import org.tessellation.storage.LocalFileSystemStorage

import eu.timepit.refined.auto._
import fs2.io.file.Path

final class GlobalSnapshotLocalFileSystemStorage[F[_]: Async: KryoSerializer] private (path: Path)
    extends LocalFileSystemStorage[F, Signed[GlobalSnapshot]](path) {

  def write(snapshot: Signed[GlobalSnapshot]): F[Unit] =
    write(toFileName(snapshot.value.ordinal), snapshot)

  def read(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] =
    read(toFileName(ordinal))

  private def toFileName(ordinal: SnapshotOrdinal) = ordinal.value.value.toString

}

object GlobalSnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer](path: Path): F[GlobalSnapshotLocalFileSystemStorage[F]] =
    Applicative[F].pure { new GlobalSnapshotLocalFileSystemStorage[F](path) }.flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}
