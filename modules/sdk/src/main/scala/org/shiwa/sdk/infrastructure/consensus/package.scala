package org.shiwa.sdk.infrastructure

import org.shiwa.schema.gossip.Ordinal
import org.shiwa.schema.peer.PeerId

package object consensus {
  type Bound = Map[PeerId, Ordinal]
}
