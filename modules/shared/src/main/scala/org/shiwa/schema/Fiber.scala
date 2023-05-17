package org.shiwa.schema

trait Fiber[A, B] {
  def reference: A
  def data: B
}
