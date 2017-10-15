import com.typesafe.sbt.SbtScalariform.ScalariformKeys
import scalariform.formatter.preferences._

import Dependencies._

lazy val bootstrap = project
  .in(file("bootstrap"))
  .settings(
    name := "bootstrap",
    libraryDependencies ++= Seq(
      jna,
      scalaTest % Test
    ),
    compileOrder in Compile := CompileOrder.JavaThenScala,
    compileOrder in Test := CompileOrder.Mixed,
    unmanagedSourceDirectories in Compile := (javaSource in Compile).value :: Nil,
    crossPaths := false,
    autoScalaLibrary := false
  )

lazy val bootstrapLibPath = "extra/bootstrap.jar"

lazy val daemon = project
  .in(file("daemon"))
  .settings(
    name := "daemon",
    libraryDependencies ++= Seq(
      akkaContribExtra,
      commonsCompress,
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
      val testClassesMappings = {
        val sourceDir = (classDirectory in Compile in test).value
        (PathFinder(sourceDir).***).pair(Path.rebase(sourceDir, targetDir))
      }
      val bootstrapAssemblyMappings = {
        val sourceAssembly = (assembly in bootstrap).value
        Seq(sourceAssembly -> targetDir / "bootstrap-assembly.jar")
      }
      val mappings = testClassesMappings ++ bootstrapAssemblyMappings
      IO.copy(mappings)
      mappings.map(_._2)
    }.dependsOn(compile in Compile in test).taskValue,
    // Native packager
    bashScriptExtraDefines += s"""addJava "-Dlandlordd.bootstrap-lib.path=$${app_home}/../$bootstrapLibPath"""",
    executableScriptName := "landlordd",
    mappings in Universal += {
      val bootstrapAssembly = (assembly in bootstrap).value
      bootstrapAssembly -> bootstrapLibPath
    },
    packageName in Universal := "landlord"
  )
  .enablePlugins(JavaAppPackaging)
  .dependsOn(bootstrap % "test->test")

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
      scalaVersion := "2.12.3",
      version      := "0.1.0-SNAPSHOT",
      ScalariformKeys.preferences := ScalariformKeys.preferences.value
        .setPreference(AlignSingleLineCaseStatements, true)
        .setPreference(DoubleIndentConstructorArguments, true)
        .setPreference(DanglingCloseParenthesis, Preserve)
    ))
  )
 .aggregate(bootstrap, daemon, test)