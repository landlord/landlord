package com.github.huntc.landlord

import java.nio.ByteOrder

import akka.NotUsed
import akka.actor.{ ActorSelection, ActorSystem, Props }
import akka.stream.scaladsl.{ Flow, Sink, Source, Tcp }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.ByteString
import java.nio.file.{ Files, Path, Paths }

import scala.concurrent.Promise
import scala.concurrent.duration._

object Main extends App {
  case class Config(
      bindIp: String = "127.0.0.1",
      bindPort: Int = 9000,
      exitTimeout: FiniteDuration = 12.seconds,
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

    opt[String]("exit-timeout").action { (x, c) =>
      c.copy(
        exitTimeout =
          Some(Duration(x))
            .collect { case fd: FiniteDuration => fd }
            .getOrElse(throw new IllegalArgumentException("Bad time - expecting a finite duration such as 10s: " + x))
      )
    }.text("The time to wait for a process to exit before interrupting. Defaults to 12 seconds (12s).")

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

  def controlFlow(
    launchInfoOp: (Source[ByteString, NotUsed], Promise[Source[ByteString, NotUsed]]) => (Int, Props),
    sendKillOp: (ActorSelection, Int) => Unit
  )(implicit system: ActorSystem, mat: Materializer): Flow[ByteString, ByteString, NotUsed] =

    Flow[ByteString]
      .prefixAndTail(1)
      .map {
        case (prefix, tail) =>
          prefix.headOption match {
            case Some(firstBytes) if firstBytes.iterator.getByte == 'l' =>
              val in = Source.single(firstBytes.drop(1)).concat(tail)
              val out = Promise[Source[ByteString, NotUsed]]()
              val (processId, jvmExecutorProps) = launchInfoOp(in, out)
              system.actorOf(jvmExecutorProps, processId.toString)
              Source.fromFutureSource(out.future)
            case Some(firstBytes) if firstBytes.iterator.getByte == 'k' =>
              Source
                .single(firstBytes.drop(1))
                .concat(tail)
                .fold(ByteString.empty) { (acc, bs) =>
                  if (acc.size + bs.size <= 8)
                    acc ++ bs
                  else
                    acc
                }
                .map { bs =>
                  val iter = bs.iterator
                  iter.getInt(ByteOrder.BIG_ENDIAN) -> iter.getInt(ByteOrder.BIG_ENDIAN)
                }
                .runForeach {
                  case (processId, signal) =>
                    sendKillOp(system.actorSelection(system.child(processId.toString)), signal)
                }
              Source.empty[ByteString]
            case _ =>
              tail.runWith(Sink.ignore)
              Source.single(ByteString("???"))
          }
      }
      .flatMapConcat(identity)

  /*
   * Main entry point.
   */
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

        def launchInfoOp(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]]): (Int, Props) = {
          val processId = connection.remoteAddress.getPort
          processId -> JvmExecutor.props(
            processId,
            properties, securityManager, config.useDefaultSecurityManager, config.preventShutdownHooks,
            stdin, config.stdinTimeout, stdout, stderr,
            in, out,
            config.exitTimeout, config.outputDrainTimeAtExit,
            config.processDirPath.resolve(processId.toString)
          )
        }

        def sendKillOp(jvmExecutor: ActorSelection, signal: Int): Unit =
          jvmExecutor ! JvmExecutor.SignalProcess(signal)

        connection.handleWith(controlFlow(launchInfoOp, sendKillOp))
      }

      system.log.info("Ready.")

    case None =>
    // arguments are bad, error message will have been displayed
  }
}
