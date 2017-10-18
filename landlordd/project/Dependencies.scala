import sbt._
import sbt.Resolver.bintrayRepo

object Versions {
  lazy val akka = "2.5.6"
  lazy val akkaContribExtra = "4.1.3"
  lazy val commonsCompress = "1.14"
  lazy val logbackClassic = "1.2.3"
  lazy val jna = "4.5.0"
  lazy val scalaTest = "3.0.3"
  lazy val scopt = "3.7.0"
}

object Dependencies {
  lazy val akkaContribExtra = ("com.typesafe.akka" %% "akka-contrib-extra" % Versions.akkaContribExtra)
    .exclude("com.typesafe.akka", "akka-cluster")
    .exclude("com.typesafe.akka", "akka-distributed-data")
    .exclude("com.typesafe.akka", "akka-http") // FIXME: The excludes aren't working for some reason
  lazy val akkSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Versions.akka
  lazy val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % Versions.akka
  lazy val commonsCompress = "org.apache.commons" % "commons-compress" % Versions.commonsCompress
  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % Versions.logbackClassic
  lazy val jna = "net.java.dev.jna" % "jna" % Versions.jna
  lazy val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest
  lazy val scopt = "com.github.scopt" %% "scopt" % Versions.scopt
}

object Resolvers {
  lazy val typesafeBintrayReleases = bintrayRepo("typesafe", "maven-releases")
}