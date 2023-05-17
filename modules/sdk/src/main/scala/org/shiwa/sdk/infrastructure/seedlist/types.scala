package org.shiwa.sdk.infrastructure.seedlist

import org.shiwa.schema.peer.PeerId
import org.shiwa.security.hex.Hex

import fs2.data.csv.RowDecoder
import fs2.data.csv.generic.semiauto.deriveRowDecoder

object types {

  case class SeedlistCSVEntry(id: String) {

    def toPeerId: PeerId = PeerId(Hex(id))
  }

  object SeedlistCSVEntry {
    implicit val rowDecoder: RowDecoder[SeedlistCSVEntry] = deriveRowDecoder
  }

}
