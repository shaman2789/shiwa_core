import sbt._

object Dependencies {
  
  object V {
    val scalaTest = "3.2.8"
    val cats = "2.6.1"
    val catsEffect = "3.2.9"
    val droste = "0.8.0"
    val shiwa = "0.8.0-SNAPSHOT"
  }

  object Libraries {
    def cats(artifact: String, version: String = V.cats): ModuleID = "org.typelevel" %% s"cats-$artifact" % version % Provided
    def droste(artifact: String): ModuleID = "io.higherkindness" %% s"droste-$artifact" % V.droste % Provided
    def shiwa(artifact: String): ModuleID = "org.shiwa" %% s"shiwa-$artifact" % V.shiwa % Provided

    val scalaTest = "org.scalatest" %% "scalatest" % V.scalaTest % Test

    val catsCore = cats("core")
    val catsEffect = cats("effect", V.catsEffect)
    val drosteCore = droste("core")
    val drosteLaws = droste("laws")
    val drosteMacros = droste("macros")

    val shiwaKernel = shiwa("kernel")
    val shiwaShared = shiwa("shared")
  }
}
