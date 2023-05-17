package org.shiwa.ext.http4s

import cats.syntax.option._

import org.shiwa.security.hash.Hash

object HashVar {
  def unapply(str: String): Option[Hash] = Hash(str).some
}
