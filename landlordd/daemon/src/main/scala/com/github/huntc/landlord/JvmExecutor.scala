package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, PoisonPill, Props, Timers }
import akka.pattern.pipe
import akka.util.ByteString
import akka.stream._
import akka.stream.scaladsl.{ BroadcastHub, Keep, Source, StreamConverters }
import java.io.{ ByteArrayOutputStream, PrintStream }
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteOrder
import java.nio.file.{ Files, Path, Paths }
import java.security.Permission
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.ref.WeakReference
import scala.util.control.NonFatal

object JvmExecutor {
  def props(
    processId: Int,
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean, preventShutdownHooks: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    exitTimeout: FiniteDuration, outputDrainTimeAtExit: FiniteDuration,
    processDirPath: Path
  ): Props =
    Props(
      new JvmExecutor(
        processId,
        properties, securityManager, useDefaultSecurityManager, preventShutdownHooks,
        stdin, stdinTimeout, stdout, stderr,
        in, out,
        exitTimeout, outputDrainTimeAtExit,
        processDirPath
      )
    )

  case class StartProcess(commandLine: String, stdin: Source[ByteString, AnyRef])
  case class SignalProcess(signal: Int)
  case object SignalProcessEot
  private case object StopSignalCheck

  private[landlord] class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    val MaxOutputSize = 8192

    override def write(c: Int): Unit =
      if (count + 1 < MaxOutputSize) super.write(c)

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      if (count + (len - off) < MaxOutputSize) super.write(b, off, len)
  }

  private[landlord] def splitMainArgs(commandLineArgs: Array[String]): (Array[String], Array[String]) = {
    val parseArgs = commandLineArgs.takeWhile(_ != "--")
    val mainArgs = commandLineArgs.diff(parseArgs).dropWhile(_ == "--")
    (parseArgs, mainArgs)
  }

  private[landlord] def resolvePaths(base: Path, relpath: Path): Seq[Path] =
    if (!relpath.toString.endsWith("*")) { // Not a glob
      List(base.resolve(relpath))
    } else {
      val stream = Files.newDirectoryStream(base.resolve(relpath.getParent), "*.{class,jar}")
      try {
        for (path <- stream.iterator().asScala.toList) yield path
      } finally {
        stream.close()
      }
    }

  private[landlord] case class ExitEarly(exitStatus: Int, errorMessage: Option[String])

  private[landlord] val SIGABRT = 6
  private[landlord] val SIGINT = 2
  private[landlord] val SIGTERM = 15

  private[landlord] def sizeToBytes(size: Int): ByteString =
    ByteString.newBuilder.putInt(size)(ByteOrder.BIG_ENDIAN).result()

  private[landlord] def processIdToBytes(processId: Int): ByteString =
    ByteString.newBuilder.putInt(processId)(ByteOrder.BIG_ENDIAN).result()

  private[landlord] def exitStatusToBytes(statusCode: Int): ByteString =
    ByteString.newBuilder.putByte('x').putInt(statusCode)(ByteOrder.BIG_ENDIAN).result()

  private[landlord] val StdoutPrefix = ByteString('o'.toByte)
  private[landlord] val StderrPrefix = ByteString('e'.toByte)

  private[landlord] val ShutdownHooksPerm = new RuntimePermission("shutdownHooks")

  private[landlord] case class ExitException(status: Int) extends SecurityException
}

/**
 * A JVM Executor creates a new thread group along with a new class loader, also redirecting
 * stdio. The goal is to make the "process" think that it is running by itself with its
 * world appearing close to what it would look like as if it were invoked directly. The
 * executor is also responsible for terminating the process.
 *
 * The incoming stream is parsed via the ProcessParameterParser stage. See its doco for a
 * full description.
 *
 * The outgoing stream is presented as follows:
 *
 * 1. The first four bytes convey the process id.
 * 2. A single UTF-8 character is then sent representing one of 'o', 'e' or 'x' (stdout, stderr, exit code).
 *
 * In the case of 'o' or 'e' then there is a four byte integer providing the length of the
 * UTF-8 characters to follow.
 *
 * Exit codes are conveyed as a four byte integer and are followed by the stream being
 * terminated. All stdout and stderr is guaranteed to be sent prior to the exit code being
 * transmitted.
 */
class JvmExecutor(
    processId: Int,
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean, preventShutdownHooks: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    exitTimeout: FiniteDuration, outputDrainTimeAtExit: FiniteDuration,
    processDirPath: Path
) extends Actor with ActorLogging with Timers {

  import JvmExecutor._

  implicit val mat: ActorMaterializer = ActorMaterializer()
  import context.dispatcher

  def stopSelfWhenDone(mat: NotUsed, done: Future[akka.Done]): NotUsed = {
    done.map(_ => PoisonPill).pipeTo(self)
    NotUsed
  }

  log.debug("Process actor starting for {}", processId)
  in
    .via(new ProcessParameterParser)
    .runFoldAsync("") {
      case (_, ProcessParameterParser.CommandLine(value)) =>
        Future.successful(value)
      case (cl, ProcessParameterParser.Archive(value)) =>
        TarStreamWriter
          .writeTarStream(
            value,
            processDirPath,
            context.system.dispatchers.lookup("akka.actor.default-blocking-io-dispatcher")
          )
          .map(_ => cl)
      case (cl, ProcessParameterParser.Stdin(value)) =>
        self ! StartProcess(cl, value)
        Future.successful(cl)
    }
    .recover {
      case e: AbruptStageTerminationException =>
        // Swallow it up - it is quite normal that our process exits with this stream
        // still active
        throw e
      case NonFatal(e) =>
        log.error(e, "Error while processing stream")
        self ! ExitEarly(1, Some(e.getMessage))
        throw e
    }
    .andThen {
      case _ => self ! SignalProcessEot
    }

  def receive: Receive =
    starting(unstopped = true)

  def starting(unstopped: Boolean): Receive = {
    case StartProcess(commandLine, stdinSource) if unstopped =>
      val commandLineArgs = commandLine.split("\u0000").toVector

      JavaArgs.parse(commandLineArgs) match {
        case Right(javaConfig) =>
          // Setup stdio streaming
          val stdinIs = stdinSource.runWith(StreamConverters.asInputStream(stdinTimeout))

          def createPrintStreamAndSource: (PrintStream, Source[ByteString, NotUsed]) = {
            val (os, source) =
              StreamConverters.asOutputStream()
                .toMat(BroadcastHub.sink)(Keep.both)
                .run()
            (new PrintStream(os), source)
          }
          val (stdoutPos, stdoutSource) = createPrintStreamAndSource
          val (stderrPos, stderrSource) = createPrintStreamAndSource

          val exitStatusPromise = Promise[Int]()

          // Resolve our process classes
          val classpath = javaConfig.cp.flatMap(cp => resolvePaths(processDirPath, Paths.get(cp)).map(_.toUri.toURL))
          val classLoader = new URLClassLoader(classpath.toArray, null)
          val classLoaderWeakRef = new WeakReference(classLoader)

          try {
            val (clsName, args) =
              javaConfig.mode match {
                case ClassExecutionMode(clsName, args) => clsName -> args
              }

            val cls = classLoader.loadClass(clsName)
            val meth = cls.getMethod("main", classOf[Array[String]])

            // Launch our "process"
            val stopInProgress = new AtomicBoolean(false)
            val processThreadGroup =
              new ThreadGroup("process-group-" + processId) {
                override def uncaughtException(t: Thread, e: Throwable): Unit = {
                  val check = synchronized {
                    val result = e match {
                      case ite: InvocationTargetException =>
                        ite.getCause match {
                          case ExitException(s) =>
                            Some(s) // It is normal for this exception to occur given that we want the process to explicitly exit
                          case null =>
                            stderr.fallback.println(s"An unexpected exception with a null cause has occurred within landlord given process $processId. Stacktrace follows.")
                            ite.printStackTrace(stderr.fallback)
                            stderr.println("Something went wrong - see Landlord's log")
                            Some(70) // EXIT_SOFTWARE, Internal Software Error as defined in BSD sysexits.h
                          case otherCause =>
                            val msg = s"An uncaught error for process $processId. Stacktrace follows. The process will continue to run unless System.exit is called."
                            stderr.fallback.println(msg)
                            otherCause.printStackTrace(stderr.fallback) // General uncaught errors within the process.
                            stderr.println(msg)
                            otherCause.printStackTrace()
                            None
                        }
                      case ExitException(s) =>
                        Some(s) // It is normal for this exception to occur given that we want the process to explicitly exit
                      case otherException =>
                        stderr.fallback.println(s"An internal error has occurred within landlord given process $processId. Stacktrace follows.")
                        otherException.printStackTrace(stderr.fallback)
                        stderr.println("Something went wrong - see Landlord's log")
                        Some(70) // EXIT_SOFTWARE, Internal Software Error as defined in BSD sysexits.h
                    }
                    result
                  }
                  check match {
                    case Some(status) =>
                      val group = t.getThreadGroup

                      if (stopInProgress.compareAndSet(false, true)) {
                        new Thread(group, { () =>
                          log.debug("Launched cleanup thread for group {}", group.getName)

                          stdin.signalClose()

                          val currentThread = Thread.currentThread

                          def activeThreads(): Seq[Thread] = {
                            val threadsInGroup = new Array[Thread](group.activeCount())

                            group.enumerate(threadsInGroup)

                            threadsInGroup.filterNot(_ == currentThread)
                          }

                          @annotation.tailrec
                          def waitForTermination(): Unit =
                            activeThreads() match {
                              case h +: _ =>
                                try {
                                  h.join()
                                } catch {
                                  case _: InterruptedException =>
                                }

                                waitForTermination()
                              case _ =>
                            }

                          waitForTermination()

                          log.debug("All threads in group {} have terminated, cleaning up", group.getName)

                          Thread.sleep(outputDrainTimeAtExit.toMillis)
                          stdoutPos.close()
                          stderrPos.close()
                          classLoaderWeakRef.get.foreach(_.close())
                          exitStatusPromise.success(status)

                          stdin.destroy()
                          stdout.destroy()
                          stderr.destroy()
                          properties.destroy()
                          securityManager.destroy()
                        }).start()
                      }
                    case None =>
                      self ! SignalProcess(SIGABRT)
                  }
                }
              }
            processThreadGroup.setDaemon(true)

            val processThread =
              new Thread(
                processThreadGroup,
                { () =>
                  stdin.init(stdinIs)
                  stdout.init(stdoutPos)
                  stderr.init(stderrPos)
                  val props = new Properties(properties.fallback)
                  props.setProperty("user.dir", processDirPath.toAbsolutePath.toString)

                  javaConfig.props.foreach {
                    case (name, value) =>
                      props.setProperty(name, value)
                  }

                  properties.init(props)
                  securityManager.init(new SecurityManager() {
                    override def checkExit(status: Int): Unit =
                      throw ExitException(status) // This will be caught as an uncaught exception
                    override def checkPermission(perm: Permission): Unit = {
                      if (perm == ShutdownHooksPerm) {
                        val message = """|: Shutdown hooks are not applicable within landlord as many applications reside in the same JVM.
                                         |Declare a `public static void trap(int signal)` for trapping signals and ultimately call
                                         |System.exit to exit.""".stripMargin
                        if (preventShutdownHooks)
                          throw new SecurityException("Error" + message)
                        else
                          System.err.println("Warning" + message)
                      } else if (useDefaultSecurityManager) {
                        super.checkPermission(perm)
                      }
                    }
                    override def checkPermission(perm: Permission, context: Object): Unit =
                      if (useDefaultSecurityManager) super.checkPermission(perm, context)
                  })
                  meth.invoke(null, args.toArray.asInstanceOf[Object])
                }: Runnable,
                "main-process-" + processId
              )

            out.success(
              Source.single(processIdToBytes(processId))
                .concat(
                  stdoutSource
                    .map(bytes => StdoutPrefix ++ sizeToBytes(bytes.size) ++ bytes)
                    .merge(stderrSource.map(bytes => StderrPrefix ++ sizeToBytes(bytes.size) ++ bytes))
                    .concat(
                      Source.fromFuture(
                        exitStatusPromise
                          .future
                          .map(exitStatusToBytes)
                      )
                    )
                )
                .watchTermination()(stopSelfWhenDone)
            )

            processThread.setContextClassLoader(classLoader)
            processThread.start()

            context.become(started(cls, processThreadGroup, exitStatusPromise))
          } catch {
            case e: UnsupportedClassVersionError =>
              classLoader.close()
              self ! ExitEarly(1, Some(if (e.getCause != null) e.getCause.toString else e.toString))
            case NonFatal(e) =>
              classLoader.close()
              self ! ExitEarly(1, Some(if (e.getCause != null) e.getCause.toString else e.toString))
          }
        case Left(errors) =>
          self ! ExitEarly(1, Some(errors.mkString(",")))
      }

    case _: StartProcess =>
      self ! ExitEarly(128 + SIGINT, None)

    case _: SignalProcess =>
      context.become(starting(unstopped = false))

    case ExitEarly(exitStatus, errorMessage) =>
      out.success(
        Source
          .single(
            processIdToBytes(processId) ++
              errorMessage.fold(ByteString.empty) { e =>
                val errorBytes = ByteString(e)
                StderrPrefix ++ sizeToBytes(errorBytes.length) ++ errorBytes
              } ++
              exitStatusToBytes(exitStatus)
          )
          .watchTermination()(stopSelfWhenDone)
      )
  }

  def started(mainClass: Class[_], processThreadGroup: ThreadGroup, exitStatusPromise: Promise[Int]): Receive = {
    case SignalProcess(signal) =>
      if (!exitStatusPromise.isCompleted && !processThreadGroup.isDestroyed) {
        new Thread(
          processThreadGroup, { () =>
          try {
            val signalMeth = mainClass.getMethod("trap", Integer.TYPE)
            signalMeth.invoke(null, signal.asInstanceOf[Object])
          } catch {
            case _: NoSuchMethodException =>
          }
        }: Runnable
        ).start()
        if (signal == SIGABRT || signal == SIGINT || signal == SIGTERM)
          timers.startSingleTimer("signalCheck", StopSignalCheck, exitTimeout)
      }

    case SignalProcessEot =>
      if (!processThreadGroup.isDestroyed) {
        new Thread(
          processThreadGroup, { () => stdin.signalClose() }
        ).start()
      }

    case StopSignalCheck =>
      val activeCount = processThreadGroup.activeCount()
      if (activeCount > 0)
        log.warning("Process {} after {} as there are {} threads still running - check that your `trap` handler is functioning correctly", processId, exitTimeout, activeCount)
      if (!exitStatusPromise.isCompleted)
        log.error("Process {} has not called `System.exit` after {} - all processes must call System.exit even with 0 so that they can be unloaded", processId, exitTimeout)
  }

  override def postStop(): Unit =
    log.debug("Process actor stopped for {}", processId)
}
