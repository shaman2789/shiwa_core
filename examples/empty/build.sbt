import Dependencies._

resolvers += Resolver.mavenLocal

ThisBuild / scalaVersion := "2.13.6"
ThisBuild / version := "0.1.0"
ThisBuild / organization := "org.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(name := "empty", libraryDependencies ++= cats ++ catsEffect ++ droste ++ refined ++ tessellation)
