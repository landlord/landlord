package com.github.huntc.landlord

import java.nio.ByteOrder

import akka.{ Done, NotUsed }
import akka.actor.{ Actor, ActorRef, ActorSelection, ActorSystem, CoordinatedShutdown, Props, Terminated }
import akka.pattern.ask
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.ByteString
import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object Main extends App {
  case class Config(
      bindDirPath: Path = Paths.get("/var/run/landlord"),
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

    opt[String]("bind-dir-path").action { (x, c) =>
      c.copy(bindDirPath = Paths.get(x))
    }.text("The Unix Domain Socket path to listen on.")

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

  object JvmExecutorReaper {
    def props: Props =
      Props(new JvmExecutorReaper)

    /**
     * Register an actor to be retained for reaping.
     */
    case class Register(actor: ActorRef)

    /**
     * Shutdown all registered executors. The message is replied to with a Done when complete.
     */
    case object Shutdown
  }

  /**
   * A JvmExecutorReaper allows executors to be registered and then subsequently shutdown gracefully in bulk.
   * The registered actors are also watched and are automatically de-registered when they terminated.
   */
  class JvmExecutorReaper extends Actor {
    import JvmExecutorReaper._

    override def receive: Receive =
      registering(List.empty)

    private def registering(actors: List[ActorRef]): Receive = {
      case Register(actor) =>
        context.become(registering(context.watch(actor) +: actors))
      case Terminated(actor) =>
        context.become(registering(actors.filterNot(_ == actor)))
      case Shutdown if actors.isEmpty =>
        sender() ! Done
      case Shutdown =>
        actors.foreach(_ ! JvmExecutor.SignalProcess(JvmExecutor.SIGTERM))
        context.become(shuttingDown(actors, sender()))
    }

    private def shuttingDown(actors: List[ActorRef], replyTo: ActorRef): Receive = {
      case Register(actor) =>
        actors.foreach(_ ! JvmExecutor.SignalProcess(JvmExecutor.SIGTERM))
        context.become(registering(context.watch(actor) +: actors))
      case _: Terminated if actors.size == 1 =>
        replyTo ! Done
      case Terminated(actor) =>
        context.become(registering(actors.filterNot(_ == actor)))
      case Shutdown =>
    }
  }

  private final val ProcessIDPrefix = "process-"

  def controlFlow(
    reaper: ActorRef,
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
              val jvmExecutor = system.actorOf(jvmExecutorProps, ProcessIDPrefix + processId)
              reaper ! JvmExecutorReaper.Register(jvmExecutor)
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
                    sendKillOp(system.actorSelection(system.child(ProcessIDPrefix + processId)), signal)
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

      val nextProcessId = new AtomicInteger(0)

      val reaper = system.actorOf(JvmExecutorReaper.props, "reaper")

      val binding =
        UnixDomainSocket()
          .bind(config.bindDirPath.resolve("landlordd.sock").toFile)
          .toMat(Sink.foreach { connection =>
            system.log.debug("New connection {}", connection)

            def launchInfoOp(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]]): (Int, Props) = {
              val processId = nextProcessId.getAndIncrement()
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

            connection.handleWith(controlFlow(reaper, launchInfoOp, sendKillOp))

          })(Keep.left)
          .run

      import system.dispatcher

      binding
        .onComplete {
          case Success(_) =>
            system.log.info("Ready.")
          case Failure(e) =>
            system.log.error(e, "Exiting")
            system.terminate()
        }

      CoordinatedShutdown(system).addTask(
        CoordinatedShutdown.PhaseBeforeActorSystemTerminate, "stopJvmExecutors") { () =>
          reaper.ask(JvmExecutorReaper.Shutdown)(config.exitTimeout)
            .mapTo[Done]
        }

      CoordinatedShutdown(system).addTask(
        CoordinatedShutdown.PhaseServiceUnbind, "unbindUnixDomainSocket") { () =>
          binding.flatMap(_.unbind().map(_ => Done))
        }

    case None =>
    // arguments are bad, error message will have been displayed
  }
}
