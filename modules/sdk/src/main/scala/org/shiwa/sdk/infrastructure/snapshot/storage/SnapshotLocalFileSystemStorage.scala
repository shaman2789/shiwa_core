package org.shiwa.sdk.infrastructure.snapshot.storage

import cats.Applicative
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.shiwa.ext.crypto._
import org.shiwa.kryo.KryoSerializer
import org.shiwa.schema.SnapshotOrdinal
import org.shiwa.schema.snapshot.Snapshot
import org.shiwa.security.hash.Hash
import org.shiwa.security.signature.Signed
import org.shiwa.storage.LocalFileSystemStorage

import better.files.File
import fs2.io.file.Path
import io.estatico.newtype.ops._

final class SnapshotLocalFileSystemStorage[F[_]: Async: KryoSerializer, S <: Snapshot[_, _]] private (path: Path)
    extends LocalFileSystemStorage[F, Signed[S]](path) {

  def write(snapshot: Signed[S]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    toHashName(snapshot.value).flatMap { hashName =>
      (exists(ordinalName), exists(hashName)).mapN {
        case (ordinalExists, hashExists) =>
          if (ordinalExists || hashExists) {
            (new Throwable("Snapshot already exists under ordinal or hash filename")).raiseError[F, Unit]
          } else {
            write(hashName, snapshot) >> link(hashName, ordinalName)
          }
      }.flatten
    }

  }

  def writeUnderOrdinal(snapshot: Signed[S]): F[Unit] = {
    val ordinalName = toOrdinalName(snapshot.value)

    write(ordinalName, snapshot)
  }

  def read(ordinal: SnapshotOrdinal): F[Option[Signed[S]]] =
    read(toOrdinalName(ordinal))

  def read(hash: Hash): F[Option[Signed[S]]] =
    read(hash.coerce[String])

  def exists(hash: Hash): F[Boolean] =
    exists(hash.coerce[String])

  def delete(ordinal: SnapshotOrdinal): F[Unit] =
    delete(toOrdinalName(ordinal))

  def getPath(hash: Hash): F[File] =
    getPath(hash.coerce[String])

  def getPath(snapshot: Signed[S]): F[File] =
    toHashName(snapshot.value).flatMap { hashName =>
      getPath(hashName)
    }

  def move(hash: Hash, to: File): F[Unit] =
    move(hash.coerce[String], to)

  def move(snapshot: Signed[S], to: File): F[Unit] =
    toHashName(snapshot.value).flatMap { hashName =>
      move(hashName, to)
    }

  def moveByOrdinal(snapshot: Signed[S], to: File): F[Unit] =
    move(toOrdinalName(snapshot), to)

  def link(snapshot: Signed[S]): F[Unit] =
    toHashName(snapshot).flatMap { hashName =>
      link(hashName, toOrdinalName(snapshot))
    }

  private def toOrdinalName(snapshot: S): String = toOrdinalName(snapshot.ordinal)
  private def toOrdinalName(ordinal: SnapshotOrdinal): String = ordinal.value.value.toString

  private def toHashName(snapshot: S): F[String] = snapshot.hashF.map(_.coerce[String])

}

object SnapshotLocalFileSystemStorage {

  def make[F[_]: Async: KryoSerializer, S <: Snapshot[_, _]](path: Path): F[SnapshotLocalFileSystemStorage[F, S]] =
    Applicative[F].pure(new SnapshotLocalFileSystemStorage[F, S](path)).flatTap { storage =>
      storage.createDirectoryIfNotExists().rethrowT
    }
}