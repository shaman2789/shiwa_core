package org.shiwa.domain.statechannel

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.kernel.Async
import cats.syntax.all._

import org.shiwa.domain.cell.{L0Cell, L0CellInput}
import org.shiwa.sdk.domain.statechannel.StateChannelValidator
import org.shiwa.sdk.domain.statechannel.StateChannelValidator.StateChannelValidationError
import org.shiwa.statechannel.StateChannelOutput
trait StateChannelService[F[_]] {
  def process(stateChannel: StateChannelOutput): F[Either[NonEmptyList[StateChannelValidationError], Unit]]
}

object StateChannelService {

  def make[F[_]: Async](mkDagCell: L0Cell.Mk[F], stateChannelValidator: StateChannelValidator[F]): StateChannelService[F] =
    new StateChannelService[F] {

      def process(stateChannelOutput: StateChannelOutput): F[Either[NonEmptyList[StateChannelValidationError], Unit]] =
        stateChannelValidator.validate(stateChannelOutput).flatMap {
          case Valid(_) =>
            mkDagCell(L0CellInput.HandleStateChannelSnapshot(stateChannelOutput))
              .run()
              .as(().asRight[NonEmptyList[StateChannelValidationError]])
          case Invalid(errors) => errors.toNonEmptyList.asLeft[Unit].pure[F]
        }

    }
}