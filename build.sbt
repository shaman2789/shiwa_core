// Данный код представляет собой конфигурационный файл для сборки проекта Shiwa на языке Scala с использованием sbt (Scala Build Tool). 

// Здесь задаются настройки для сборки проекта, добавляются репозитории, указываются зависимости от внешних библиотек, 
// настраивается интеграция с плагинами scalafix и scalafmt, а также прописываются настройки для создания Docker-образов. 
// Определены основные модули проекта, их зависимости и настройки. Также заданы различные флаги для сборки, такие как 
// `fork`, `cancelable`, `onChangedBuildSource`. 

import Dependencies._ //импортирует зависимости, указанные в файле Dependencies.

ThisBuild / scalaVersion := "2.13.10"  // устанавливает версию языка программирования Scala в 2.13.10.
ThisBuild / organization := "org.shiwa" //устанавливает имя организации для проекта.
ThisBuild / organizationName := "shiwa-network" //устанавливает название организации для проекта.

ThisBuild / evictionErrorLevel := Level.Warn //задает уровень сообщения об ошибке при конфликтах версий зависимостей.
ThisBuild / scalafixDependencies += Libraries.organizeImports //добавляет scalafix в зависимости проекта.

resolvers += Resolver.sonatypeRepo("snapshots") //добавляет репозиторий для загрузки зависимостей проекта.

val scalafixCommonSettings = inConfig(IntegrationTest)(scalafixConfigSettings(IntegrationTest)) //оздает общие настройки scalafix для интеграционного тестирования.

bloopExportJarClassifiers in Global := Some(Set("sources")) //настройка Bloop, позволяющая экспортировать исходный код.

val ghTokenSource = TokenSource.GitConfig("github.token") || TokenSource.Environment("GITHUB_TOKEN") //устанавливает источник токена GitHub.

githubTokenSource := ghTokenSource //задает общие настройки для проекта, такие как настройки компилятора, resolvers и источника токена GitHub.

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Ymacro-annotations", "-Yrangepos", "-Wconf:cat=unused:info", "-language:reflectiveCalls"),
  scalafmtOnCompile := true,
  scalafixOnCompile := true,
  resolvers ++= List(
    Resolver.sonatypeRepo("snapshots"),
    Resolver.githubPackages("abankowski", "http-request-signer")
  ),
  githubTokenSource := ghTokenSource
)     

lazy val commonTestSettings = Seq(  //задает общие настройки для тестов проекта.
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  libraryDependencies ++= Seq(
    Libraries.weaverCats,
    Libraries.weaverDiscipline,
    Libraries.weaverScalaCheck,
    Libraries.catsEffectTestkit
  ).map(_ % Test)
)

ThisBuild / assemblyMergeStrategy := {   //настройка сборки проекта, которая задает правила для слияния файлов.
  case "logback.xml"                                       => MergeStrategy.first
  case x if x.contains("io.netty.versions.properties")     => MergeStrategy.discard
  case PathList(xs @ _*) if xs.last == "module-info.class" => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}

Global / fork := true //задает использование отдельного процесса для выполнения тестов.
Global / cancelable := true //задает возможность прерывания выполнения задач.
Global / onChangedBuildSource := ReloadOnSourceChanges //задает автоматическую перезагрузку проекта при изменении исходного кода


lazy val dockerSettings = Seq(  //задает настройки для Docker-образа проекта.
  Docker / packageName := "shiwa",
  dockerBaseImage := "openjdk:jre-alpine"
)

lazy val root = (project in file(".")) //создает корневой проект с именем "shiwa" и устанавливает зависимости от других модулей.
  .settings(
    name := "shiwa"
  )
  .aggregate(keytool, kernel, shared, core, testShared, wallet, sdk, dagL1, rosetta, currencyL0, currencyL1, tools)

lazy val kernel = (project in file("modules/kernel")) // создает модуль "kernel" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .dependsOn(shared, testShared % Test)
  .settings(
    name := "shiwa-kernel",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.semanticDB,
      Libraries.drosteCore,
      Libraries.fs2Core
    )
  )

lazy val wallet = (project in file("modules/wallet")) // создает модуль "wallet" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .dependsOn(keytool, shared, testShared % Test)
  .settings(
    name := "shiwa-wallet",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.circeFs2,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.cirisCore,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.fs2IO,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime
    )
  )

lazy val keytool = (project in file("modules/keytool")) // создает модуль "keytools" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .dependsOn(shared, testShared % Test)
  .settings(
    name := "shiwa-keytool",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.comcast,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2IO,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.refinedCore,
      Libraries.refinedCats
    )
  )

lazy val shared = (project in file("modules/shared")) // создает модуль "shared" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .dependsOn(testShared % Test)
  .settings(
    name := "shiwa-shared",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.shiwa",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.bc,
      Libraries.bcExtensions,
      Libraries.betterFiles,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.chill,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.comcast,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoScalacheck,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.enumeratumCore,
      Libraries.enumeratumCirce,
      Libraries.guava,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.refinedScalacheck,
      Libraries.http4sCore
    )
  )
lazy val testShared = (project in file("modules/test-shared")) // создает модуль "test-shared" и задает его зависимости и настройки.
  .configs(IntegrationTest)
  .settings(
    name := "shiwa-test-shared",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.catsLaws,
      Libraries.log4catsNoOp,
      Libraries.monocleLaw,
      Libraries.refinedScalacheck,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.fs2Core,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.weaverCats,
      Libraries.weaverDiscipline,
      Libraries.weaverScalaCheck
    )
  )

lazy val sdk = (project in file("modules/sdk"))  // создает модуль "sdk" и задает его зависимости и настройки.
  .dependsOn(shared % "compile->compile;test->test", testShared % Test, keytool, kernel)
  .configs(IntegrationTest)
  .settings(
    name := "shiwa-sdk",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sCore,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.jawnParser,
      Libraries.jawnAst,
      Libraries.jawnFs2,
      Libraries.declineCore,
      Libraries.declineEffect,
      Libraries.declineRefined,
      Libraries.logback,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.log4cats,
      Libraries.micrometerPrometheusRegistry,
      Libraries.shapeless
    )
  )

lazy val rosetta = (project in file("modules/rosetta")) // создает модуль "rosetta" и задает его зависимости и настройки.
  .dependsOn(kernel, shared % "compile->compile;test->test", sdk, testShared % Test)
  .settings(
    name := "shiwa-rosetta",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.newtype,
      Libraries.refinedCore
    )
  )

lazy val dagL1 = (project in file("modules/dag-l1")) // создает модуль "dag-l1" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(kernel, shared % "compile->compile;test->test", sdk, testShared % Test)
  .configs(IntegrationTest)
  .settings(
    name := "shiwa-dag-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeShapes,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.derevoCore,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.flyway,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.monocleMacro,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.sqlite
    )
  )
lazy val tools = (project in file("modules/tools")) // создает модуль "tools" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(core, dagL1)
  .settings(
    name := "shiwa-tools",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    run / connectInput := true,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.fs2Core,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.http4sDsl,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce
    )
  )
lazy val core = (project in file("modules/core")) // создает модуль "core" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, sdk)
  .settings(
    name := "shiwa-core",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    dockerSettings,
    makeBatScripts := Seq(),
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB,
      Libraries.cats,
      Libraries.catsEffect,
      Libraries.catsRetry,
      Libraries.circeCore,
      Libraries.circeGeneric,
      Libraries.circeParser,
      Libraries.circeRefined,
      Libraries.circeShapes,
      Libraries.cirisCore,
      Libraries.cirisEnum,
      Libraries.cirisRefined,
      Libraries.derevoCore,
      Libraries.derevoCats,
      Libraries.derevoCirce,
      Libraries.doobieCore,
      Libraries.doobieHikari,
      Libraries.doobieH2,
      Libraries.doobieQuill,
      Libraries.drosteCore,
      Libraries.drosteLaws,
      Libraries.drosteMacros,
      Libraries.fs2Core,
      Libraries.flyway,
      Libraries.fs2DataCsv,
      Libraries.fs2DataCsvGeneric,
      Libraries.h2,
      Libraries.http4sDsl,
      Libraries.http4sServer,
      Libraries.http4sClient,
      Libraries.http4sCirce,
      Libraries.http4sJwtAuth,
      Libraries.httpSignerCore,
      Libraries.httpSignerHttp4s,
      Libraries.javaxCrypto,
      Libraries.log4cats,
      Libraries.logback % Runtime,
      Libraries.logstashLogbackEncoder % Runtime,
      Libraries.mapref,
      Libraries.monocleCore,
      Libraries.newtype,
      Libraries.redis4catsEffects,
      Libraries.redis4catsLog4cats,
      Libraries.refinedCore,
      Libraries.refinedCats,
      Libraries.skunkCore,
      Libraries.skunkCirce,
      Libraries.squants
    )
  )

lazy val currencyL1 = (project in file("modules/currency-l1")) // создает модуль "currency-l1" и задает его зависимости и настройки.
  .dependsOn(dagL1, sdk, shared)
  .settings(
    name := "shiwa-currency-l1",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB
    )
  )

lazy val currencyL0 = (project in file("modules/currency-l0"))  // создает модуль "currency-l0" и задает его зависимости и настройки.
  .enablePlugins(AshScriptPlugin)
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(JavaAppPackaging)
  .dependsOn(keytool, kernel, shared % "compile->compile;test->test", testShared % Test, sdk)
  .settings(
    name := "shiwa-currency-l0",
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.shiwa.currency",
    Defaults.itSettings,
    scalafixCommonSettings,
    commonSettings,
    commonTestSettings,
    libraryDependencies ++= Seq(
      CompilerPlugin.kindProjector,
      CompilerPlugin.betterMonadicFor,
      CompilerPlugin.semanticDB
    )
  )

addCommandAlias("runLinter", ";scalafixAll --rules OrganizeImports")
