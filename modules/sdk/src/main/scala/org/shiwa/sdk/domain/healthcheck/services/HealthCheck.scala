package org.shiwa.sdk.domain.healthcheck.services

trait HealthCheck[F[_]] {

  def trigger(): F[Unit]

}
