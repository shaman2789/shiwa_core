package org.shiwa.sdk.infrastructure.healthcheck.ping

import org.shiwa.schema._
import org.shiwa.schema.cluster.SessionToken
import org.shiwa.schema.peer.PeerId
import org.shiwa.sdk.domain.healthcheck.consensus.types.HealthCheckKey

import com.comcast.ip4s.{Host, Port}
import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, show)
case class PingHealthCheckKey(id: PeerId, ip: Host, p2pPort: Port, session: SessionToken) extends HealthCheckKey
