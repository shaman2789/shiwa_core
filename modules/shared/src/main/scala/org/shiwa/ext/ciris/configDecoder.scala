package org.shiwa.ext.ciris

import org.shiwa.ext.derevo.Derive

import _root_.ciris.ConfigDecoder

class configDecoder extends Derive[Decoder.Id]

object Decoder {
  type Id[A] = ConfigDecoder[String, A]
}
