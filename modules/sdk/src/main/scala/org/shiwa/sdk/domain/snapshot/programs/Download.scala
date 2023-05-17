package org.shiwa.sdk.domain.snapshot.programs

trait Download[F[_]] {
  def download: F[Unit]
}
