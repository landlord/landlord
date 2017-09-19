import sbt._
import sbt.Resolver.bintrayRepo

object Dependencies {
  lazy val akkaContribExtra = ("com.typesafe.akka" %% "akka-contrib-extra" % "4.1.3")
    .exclude("com.typesafe.akka", "akka-cluster")
    .exclude("com.typesafe.akka", "akka-distributed-data")
    .exclude("com.typesafe.akka", "akka-http") // FIXME: The excludes aren't working for some reason
  lazy val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % "2.5.6"
  lazy val commonsCompress = "org.apache.commons" % "commons-compress" % "1.14"
  lazy val jna = "net.java.dev.jna" % "jna" % "4.5.0"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.3"
  lazy val scopt = "com.github.scopt" %% "scopt" % "3.7.0"
}

object Resolvers {
  lazy val typesafeBintrayReleases = bintrayRepo("typesafe", "maven-releases")
}