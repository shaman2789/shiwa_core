package org.shiwa.sdk.domain.healthcheck

trait HealthChecks[F[_]] {
  def trigger(): F[Unit]
}
