import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import com.typesafe.sbt.packager.docker._
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
    packageName in Docker := "landlordd",
    dockerUsername := Some("landlord"),
    dockerCommands := dockerCommands.value.flatMap {
      case cmd @ Cmd("ADD", _) => Seq(
        cmd,
        Cmd(
          "RUN",
          s"""|chmod -R g+x /opt/docker/bin && \\
              |chmod -R g+w /opt/docker
           """.stripMargin)

      )
      case cmd @ Cmd("WORKDIR", _) => Seq(
        cmd,
        Cmd(
          "RUN",
          s"""|apk add --no-cache dumb-init shadow && \\
              |mkdir -p /var/run/landlord && \\
              |chown ${daemonUser.value}:${daemonGroup.value} /var/run/landlord && \\
              |chmod 770 /var/run/landlord && \\
              |usermod -d /var/run/landlord ${daemonUser.value}
              |""".stripMargin)
      )
      case cmd => Seq(cmd)
    },
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerEntrypoint := Seq("/usr/bin/dumb-init", "--single-child", "--") ++ dockerEntrypoint.value,
    bashScriptExtraDefines ++= Seq(
      // Configuration for when running in a container
      """addJava "-XX:+UnlockExperimentalVMOptions"""",
      """addJava "-XX:+UseCGroupMemoryLimitForHeap""""
    )
  )
  .enablePlugins(AshScriptPlugin, JavaAppPackaging)

lazy val test = project
  .in(file("test"))
  .settings(
    name := "test"
  )

lazy val landlordd = project
  .in(file("."))
  .settings(
    name := "landlordd",
    inThisBuild(List(
      organization := "com.github.huntc",
      scalaVersion := "2.12.6",
      version      := sys.env.getOrElse("RELEASE_VERSION", "0.1.0-SNAPSHOT"),
      scalacOptions ++= Seq("-unchecked", "-deprecation"),
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(DoubleIndentConstructorArguments, true)
        .setPreference(DanglingCloseParenthesis, Preserve)
    ))
  )
 .aggregate(daemon, test)
