package org.shiwa.security

trait Encodable {
  def toEncode: AnyRef = this
}
