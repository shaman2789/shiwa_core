package org.shiwa.schema

import org.shiwa.schema.address.SHIAddressRefined

import eu.timepit.refined.refineV
import weaver.FunSuite
import weaver.scalacheck.Checkers

object AddressSuite extends FunSuite with Checkers {
  val validAddress = "SHI2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQgech"

  test("correct SHI Address should pass validation") {
    val result = refineV[SHIAddressRefined].apply[String](validAddress)

    expect(result.isRight)
  }

  test("stardust collective SHI Address should pass validation") {
    val result = refineV[SHIAddressRefined].apply[String](StardustCollective.address)

    expect(result.isRight)
  }

  test("too long SHI Address should fail validation") {
    val result = refineV[SHIAddressRefined].apply[String](validAddress + "a")

    expect(result.isLeft)
  }

  test("SHI Address with wrong parity should fail validation") {
    val result = refineV[SHIAddressRefined].apply[String]("SHI1" + validAddress.substring(4))

    expect(result.isLeft)
  }

  test("SHI Address with non-base58 character should fail validation") {
    val result = refineV[SHIAddressRefined].apply[String](validAddress.replace("h", "0"))

    expect(result.isLeft)
  }
}
