import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences._

import Dependencies._

lazy val daemon = project
  .in(file("daemon"))
  .settings(
    name := "daemon",
    libraryDependencies ++= Seq(
      akkaStream,
      akkSlf4j,
      alpakkaUnixDomainSocket,
      commonsCompress,
      logbackClassic,
      scopt,
      akkaTestKit % Test,
      scalaTest % Test
    ),
    resolvers += Resolvers.typesafeBintrayReleases,
    scriptClasspathOrdering := {
      val assemblyFile = assembly.value
      Seq(assemblyFile -> ("lib/" + assemblyFile.getName))
    },
    sourceGenerators in Compile += Def.task {
      val versionFile = (sourceManaged in Compile).value / "Version.scala"
      val versionSource =
        s"""|package com.github.huntc.landlord
            |
            |object Version {
            |  val executableScriptName = "${(executableScriptName in Universal).value}"
            |  val current = "${version.value}"
            |}
            """.stripMargin
      IO.write(versionFile, versionSource)
      Seq(versionFile)
    }.taskValue,
    // Provide the test classes as resources for our tests
    resourceGenerators in Test += Def.task {
      val targetDir = (resourceManaged in Test).value
      val mappings = {
        val sourceDir = (classDirectory in Compile in test).value
        PathFinder(sourceDir).allPaths.pair(Path.rebase(sourceDir, targetDir))
      }
      IO.copy(mappings)
      mappings.map(_._2)
    }.dependsOn(compile in Compile in test).taskValue,
    // Native packager
    executableScriptName := "landlordd",
    packageName in Universal := "landlord",
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerExposedPorts := List(9000)
  )
  .enablePlugins(AshScriptPlugin, JavaAppPackaging)

lazy val test = project
  .in(file("test"))
  .settings(
    name := "test"
  )

lazy val root = project
  .in(file("."))
  .settings(
    inThisBuild(List(
      organization := "com.github.huntc",
      scalaVersion := "2.12.4",
      version      := "0.1.0-SNAPSHOT",
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(DoubleIndentConstructorArguments, true)
        .setPreference(DanglingCloseParenthesis, Preserve)
    ))
  )
 .aggregate(daemon, test)