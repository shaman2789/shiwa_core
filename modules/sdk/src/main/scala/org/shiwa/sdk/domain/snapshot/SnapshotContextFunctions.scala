package org.shiwa.sdk.domain.snapshot

import org.shiwa.security.signature.Signed

trait SnapshotContextFunctions[F[_], Artifact, Context] {
  def createContext(
    context: Context,
    lastArtifact: Artifact,
    signedArtifact: Signed[Artifact]
  ): F[Context]
}
