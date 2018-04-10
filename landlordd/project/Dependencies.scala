import sbt._
import sbt.Resolver.bintrayRepo

object Versions {
  lazy val akka = "2.5.8"
  lazy val alpakka = "0.18+6-1a29fcff"
  lazy val commonsCompress = "1.15"
  lazy val logbackClassic = "1.2.3"
  lazy val scalaTest = "3.0.4"
  lazy val scopt = "3.7.0"
}

object Dependencies {
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream" % Versions.akka
  lazy val akkSlf4j = "com.typesafe.akka" %% "akka-slf4j" % Versions.akka
  lazy val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % Versions.akka
  lazy val alpakkaUnixDomainSocket = "com.lightbend.akka" %% "akka-stream-alpakka-unix-domain-socket" % Versions.alpakka
  lazy val commonsCompress = "org.apache.commons" % "commons-compress" % Versions.commonsCompress
  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % Versions.logbackClassic
  lazy val scalaTest = "org.scalatest" %% "scalatest" % Versions.scalaTest
  lazy val scopt = "com.github.scopt" %% "scopt" % Versions.scopt
}

object Resolvers {
  lazy val typesafeBintrayReleases = bintrayRepo("typesafe", "maven-releases")
}
