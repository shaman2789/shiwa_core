package org.shiwa.domain.rewards

import cats.data.NonEmptySet

import org.shiwa.infrastructure.rewards.DistributionState
import org.shiwa.schema.ID.Id
import org.shiwa.schema.epoch.EpochProgress

trait RewardsDistributor[F[_]] {

  def distribute(epochProgress: EpochProgress, facilitators: NonEmptySet[Id]): DistributionState[F]

}
