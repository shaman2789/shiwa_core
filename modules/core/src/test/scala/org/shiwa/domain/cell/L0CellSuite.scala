package org.shiwa.domain.cell

import cats.effect.IO
import cats.effect.std.Queue

import org.shiwa.block.generators.signedSHIBlockGen
import org.shiwa.kernel.Cell
import org.shiwa.schema.block.SHIBlock
import org.shiwa.security.signature.Signed

import eu.timepit.refined.auto._
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object L0CellSuite extends SimpleMutableIOSuite with Checkers {

  def mkL0CellMk(queue: Queue[IO, Signed[SHIBlock]]) =
    L0Cell.mkL0Cell[IO](queue, null)

  test("pass dag block to the queue") { _ =>
    forall(signedSHIBlockGen) { dagBlock =>
      for {
        dagBlockQueue <- Queue.unbounded[IO, Signed[SHIBlock]]
        mkDagCell = mkL0CellMk(dagBlockQueue)
        cell = mkDagCell(L0CellInput.HandleSHIL1(dagBlock))
        res <- cell.run()
        sentData <- dagBlockQueue.tryTake
      } yield expect.same((res, sentData.get), (Right(Cell.NullTerminal), dagBlock))
    }
  }
}
