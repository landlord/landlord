package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, Source, Tcp }
import akka.stream.ActorMaterializer
import akka.util.ByteString
import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.duration._
import scala.concurrent.Promise

object Main extends App {
  case class Config(
      bindIp: String = "127.0.0.1",
      bindPort: Int = 9000,
      bootstrapLibPath: Path = Paths.get(sys.props.getOrElse("landlordd.bootstrap-lib.path", "")),
      processDirPath: Path = Files.createTempDirectory("jvm-executor"))

  val parser = new scopt.OptionParser[Config](Version.executableScriptName) {
    head(Version.executableScriptName, Version.current)
    note("Daemon for sharing JVM memory between JVM processes - used with a client as a substitute for the `java` command")

    opt[String]("bind-ip").action { (x, c) =>
      c.copy(bindIp = x)
    }.text("The IP address to listen on.")

    opt[Int]("bind-port").action { (x, c) =>
      c.copy(bindPort = x)
    }.text("The port to listen on")

    opt[String]("bootstrap-lib-path").action { (x, c) =>
      c.copy(bootstrapLibPath = Paths.get(x))
    }.text("The path to bootstrap library used when forking Java processes.")

    opt[String]("process-dir-path").action { (x, c) =>
      c.copy(processDirPath = Paths.get(x))
    }.text("The path to use for the working directory for the process.")
  }

  parser.parse(args, Config()) match {
    case Some(config) =>
      implicit val system = ActorSystem("landlordd")
      implicit val mat = ActorMaterializer()

      Tcp().bind(config.bindIp, config.bindPort).runForeach { connection =>
        system.log.info("New connection from {}", connection.remoteAddress)

        val flow =
          Flow[ByteString]
            .prefixAndTail(0) // Lift our incoming stream into another Source that we can pass around
            .map {
              case (_, in) =>
                val out = Promise[Source[ByteString, NotUsed]]()
                system.actorOf(JvmExecutor.props(
                  in,
                  out,
                  config.bootstrapLibPath,
                  config.processDirPath.resolve(connection.remoteAddress.getPort.toString)
                ))
                Source.fromFutureSource(out.future)
            }
            .flatMapConcat(identity)

        connection.handleWith(flow)
      }
      system.log.info("Ready.")

    case None =>
    // arguments are bad, error message will have been displayed
  }
}
