/*Функциональность распределения вознаграждений среди посредников/валидаторов.

 *trait FacilitatorDistributor[F[_]] - Определяет трейт FacilitatorDistributor, который предоставляет
      абстракцию для распределения вознаграждений среди посредников/валидаторов. Он содержит метод distribute,
      который выполняет распределение и возвращает DistributionState[F].
 *object FacilitatorDistributor - Объект FacilitatorDistributor, который содержит метод make для создания
      объекта FacilitatorDistributor и выполнения распределения.
Описание методов:
 *def make[F[_]: Async: SecurityProvider]: FacilitatorDistributor[F] - Создает объект FacilitatorDistributor
    для асинхронного эффекта F с использованием указанного SecurityProvider. Метод принимает random (генератор случайных чисел)
    и facilitators (набор фасилитаторов) и возвращает DistributionState[F], который представляет состояние распределения*/

package org.shiwa.infrastructure.rewards

import cats.data.{NonEmptySet, StateT}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.shiwa.ext.refined._
import org.shiwa.schema.ID.Id
import org.shiwa.schema.balance.Amount
import org.shiwa.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.ops._

trait FacilitatorDistributor[F[_]] {
  def distribute(random: Random[F], facilitators: NonEmptySet[Id]): DistributionState[F]
}

object FacilitatorDistributor {

  def make[F[_]: Async: SecurityProvider]: FacilitatorDistributor[F] =
    (random, facilitators) =>
      StateT { amount =>
        facilitators.toList
          .traverse(_.toAddress)
          .flatMap(random.shuffleList)
          .map { addresses =>
            for {
              (bottomAmount, reminder) <- amount.coerce /% NonNegLong.unsafeFrom(addresses.length.toLong)
              topAmount <- bottomAmount + 1L

              (topRewards, bottomRewards) = addresses
                .splitAt(reminder.toInt)
                .bimap(_.map(_ -> Amount(topAmount)), _.map(_ -> Amount(bottomAmount)))

              allRewards = topRewards ++ bottomRewards

              rewardsSum <- allRewards.map(_._2.coerce).sumAll
              remainingAmount <- amount.coerce - rewardsSum
            } yield (Amount(remainingAmount), allRewards)
          }
          .map(_.liftTo[F])
          .flatten
      }

}
