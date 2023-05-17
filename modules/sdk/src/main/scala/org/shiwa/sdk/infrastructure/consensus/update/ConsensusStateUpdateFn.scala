package org.shiwa.sdk.infrastructure.consensus.update

import cats.data.StateT

import org.shiwa.sdk.infrastructure.consensus.{ConsensusResources, ConsensusState}

trait ConsensusStateUpdateFn[F[_], Key, Artifact, Context, Action]
    extends (ConsensusResources[Artifact] => StateT[F, ConsensusState[Key, Artifact, Context], Action])
