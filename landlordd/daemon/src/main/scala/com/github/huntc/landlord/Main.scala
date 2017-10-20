package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Flow, Source, Tcp }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.ByteString
import java.nio.file.{ Files, Path, Paths }
import scala.concurrent.Promise
import scala.concurrent.duration._

object Main extends App {
  case class Config(
      bindIp: String = "127.0.0.1",
      bindPort: Int = 9000,
      preventShutdownHooks: Boolean = false,
      outputDrainTimeAtExit: FiniteDuration = 100.milliseconds,
      processDirPath: Path = Files.createTempDirectory("jvm-executor"),
      stdinTimeout: FiniteDuration = 1.hour,
      useDefaultSecurityManager: Boolean = false
  )

  val parser = new scopt.OptionParser[Config](Version.executableScriptName) {
    head(Version.executableScriptName, Version.current)
    note("Daemon for sharing JVM memory between JVM processes - used with a client as a substitute for the `java` command")

    opt[String]("bind-ip").action { (x, c) =>
      c.copy(bindIp = x)
    }.text("The IP address to listen on.")

    opt[Int]("bind-port").action { (x, c) =>
      c.copy(bindPort = x)
    }.text("The port to listen on")

    opt[Unit]("prevent-shutdown-hooks").action { (_, c) =>
      c.copy(preventShutdownHooks = true)
    }.text("When set, a security exception will be thrown if shutdown hooks are detected within a process. Defaults to false, which then just warns on stderr.")

    opt[String]("output-drain-time-at-exit").action { (x, c) =>
      c.copy(
        outputDrainTimeAtExit =
          Some(Duration(x))
            .collect { case fd: FiniteDuration => fd }
            .getOrElse(throw new IllegalArgumentException("Bad time - expecting a finite duration such as 100ms: " + x))
      )
    }.text("The amount of time to wait for a process to have its stdout/stderr transmitted at exit. Defaults to 100 milliseconds (500ms).")

    opt[String]("process-dir-path").action { (x, c) =>
      c.copy(processDirPath = Paths.get(x))
    }.text("The path to use for the working directory for the process.")

    opt[String]("stdin-timeout").action { (x, c) =>
      c.copy(
        stdinTimeout =
          Some(Duration(x))
            .collect { case fd: FiniteDuration => fd }
            .getOrElse(throw new IllegalArgumentException("Bad time - expecting a finite duration such as 1h: " + x))
      )
    }.text("The maximum amount of time to block waiting on stdin. Defaults to 1 hour (1h).")

    opt[Boolean]("use-default-security-manager").action { (x, c) =>
      c.copy(useDefaultSecurityManager = x)
    }.text("When true, the JVM's default security manager will be used for processes. The option defaults to false.")
  }

  parser.parse(args, Config()) match {
    case Some(config) =>
      val stdin = new ThreadGroupInputStream(System.in)
      val stdout = new ThreadGroupPrintStream(System.out)
      val stderr = new ThreadGroupPrintStream(System.err)
      System.setIn(stdin)
      System.setOut(stdout)
      System.setErr(stderr)

      val properties = new ThreadGroupProperties(System.getProperties)
      System.setProperties(properties)

      val securityManager = new ThreadGroupSecurityManager(System.getSecurityManager)
      System.setSecurityManager(securityManager)

      implicit val system: ActorSystem = ActorSystem("landlordd")
      implicit val mat: Materializer = ActorMaterializer()

      Tcp().bind(config.bindIp, config.bindPort).runForeach { connection =>
        system.log.info("New connection from {}", connection.remoteAddress)

        val flow =
          Flow[ByteString]
            .prefixAndTail(0) // Lift our incoming stream into another Source that we can pass it around
            .map {
              case (_, in) =>
                val processId = connection.remoteAddress.getPort.toString
                val out = Promise[Source[ByteString, NotUsed]]()
                system.actorOf(JvmExecutor.props(
                  processId,
                  properties, securityManager, config.useDefaultSecurityManager, config.preventShutdownHooks,
                  stdin, config.stdinTimeout, stdout, stderr,
                  in, out,
                  config.outputDrainTimeAtExit,
                  config.processDirPath.resolve(processId)
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
