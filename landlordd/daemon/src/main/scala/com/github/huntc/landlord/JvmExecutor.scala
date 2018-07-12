package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, Props, Timers }
import akka.util.ByteString
import akka.stream._
import akka.stream.scaladsl.{ BroadcastHub, Keep, Source, StreamConverters }
import java.io.{ ByteArrayOutputStream, File, IOException, PrintStream }
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteOrder
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._
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
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    exitTimeout: FiniteDuration, outputDrainTimeAtExit: FiniteDuration,
    heatbeatInterval: FiniteDuration,
    processDirPath: Path,
    exposedPropNames: Seq[String]
  ): Props =
    Props(
      new JvmExecutor(
        processId,
        properties, securityManager, useDefaultSecurityManager,
        stdin, stdinTimeout, stdout, stderr,
        in, out,
        exitTimeout, outputDrainTimeAtExit,
        heatbeatInterval,
        processDirPath,
        exposedPropNames
      )
    )

  case class StartProcess(commandLine: String, stdin: Source[ByteString, AnyRef])
  case class SignalProcess(signal: Int)
  private case object ConnectionReadClosed
  private case object ConnectionWriteClosed
  private case object Stop

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

  /**
   * Determines all of the active threads that are (recursively) a member of
   * a thread group.
   */
  private[landlord] def activeThreads(group: ThreadGroup): Set[Thread] =
    Thread
      .getAllStackTraces
      .keySet
      .asScala
      .filter(t => memberOfThreadGroup(t.getThreadGroup, group))
      .toSet

  /**
   * Determines if `subject` is a member of `group`, recursively.
   */
  @annotation.tailrec
  def memberOfThreadGroup(subject: ThreadGroup, group: ThreadGroup): Boolean =
    if (subject == null || group == null)
      false
    else if (subject == group)
      true
    else
      memberOfThreadGroup(subject.getParent, group)

  /**
   * Given a `ThreadGroup`, unregisters all shutdown hooks that
   * 'belong' to that thread group and returns them.
   *
   * If there is a failure obtaining the shutdown hooks, an
   * `IllegalStateException` is thrown.
   */
  private[landlord] def removeShutdownHooks(group: ThreadGroup): Set[Thread] = {
    var hooks: Set[Thread] = null

    try {
      val hooksField = Class
        .forName("java.lang.ApplicationShutdownHooks")
        .getDeclaredField("hooks")

      hooksField.setAccessible(true)

      val allHooks = hooksField.get(null)

      if (allHooks != null) {
        hooks = allHooks
          .asInstanceOf[java.util.IdentityHashMap[Thread, Thread]]
          .keySet
          .asScala
          .filter(t => memberOfThreadGroup(t.getThreadGroup, group))
          .toSet
      }
    } catch {
      case _: NoSuchFieldException | _: ClassCastException | _: IllegalAccessException | _: IllegalArgumentException | _: ExceptionInInitializerError =>
      // we ignore any expected reflection related exceptions as we'll throw our own IllegalStateException below
      // other exception types are unexpected and thus will be thrown
    }

    if (hooks == null)
      throw new IllegalStateException("Could not access java.lang.ApplicationShutdownHooks.fields")

    hooks.foreach { hook =>
      Runtime.getRuntime.removeShutdownHook(hook)
    }

    hooks
  }

  // From https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#getProperties--
  private[landlord] val StandardPropNames = List(
    "java.version",
    "java.vendor",
    "java.vendor.url",
    "java.home",
    "java.vm.specification.version",
    "java.vm.specification.vendor",
    "java.vm.specification.name",
    "java.vm.version",
    "java.vm.vendor",
    "java.vm.name",
    "java.specification.version",
    "java.specification.vendor",
    "java.specification.name",
    "java.class.version",
    "java.class.path",
    "java.library.path",
    "java.io.tmpdir",
    "java.compiler",
    "java.ext.dirs",
    "os.name",
    "os.arch",
    "os.version",
    "file.separator",
    "path.separator",
    "line.separator",
    "user.name",
    "user.home",
    "user.dir"
  )

  // Inspired by https://gist.github.com/polymorphic/6894163
  private[landlord] def deleteFiles(path: Path): Unit =
    try {
      if (Files.exists(path) && Files.isDirectory(path)) {
        Files.walkFileTree(path, new FileVisitor[Path] {
          def visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE

          def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          def preVisitDirectory(dir: Path, attrs: BasicFileAttributes) = FileVisitResult.CONTINUE

          def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            Files.delete(dir)
            FileVisitResult.CONTINUE
          }
        })
      }
    } catch {
      case NonFatal(_) =>
    }
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
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    exitTimeout: FiniteDuration, outputDrainTimeAtExit: FiniteDuration,
    heatbeatInterval: FiniteDuration,
    processDirPath: Path,
    exposedPropNames: Seq[String]
) extends Actor with ActorLogging with Timers {

  import JvmExecutor._

  implicit val mat: ActorMaterializer = ActorMaterializer()
  import context.dispatcher

  val blockingDispatcher = context.system.dispatchers.lookup("akka.actor.default-blocking-io-dispatcher")

  log.debug("Process actor starting for {}", processId)
  in
    .via(new ProcessParameterParser)
    .runFoldAsync("") {
      case (_, ProcessParameterParser.CommandLine(value)) =>
        Future.successful(value)
      case (cl, ProcessParameterParser.Archive(value)) =>
        Future(deleteFiles(processDirPath))(blockingDispatcher)
          .flatMap(_ =>
            TarStreamWriter
              .writeTarStream(
                value,
                processDirPath,
                blockingDispatcher
              )
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
      case _ => self ! ConnectionReadClosed
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
                case ClassExecutionMode(c, a) => c -> a
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

                          activeThreads(group)
                            .filterNot(_.getName.startsWith(context.system.name))
                            .foreach { thread =>
                              thread.interrupt()
                            }

                          try {
                            val shutdownHooks = removeShutdownHooks(group)

                            log.debug("Running {} shutdown hooks", shutdownHooks.size)

                            shutdownHooks.foreach { thread =>
                              thread.start()
                            }
                          } catch {
                            case _: IllegalStateException =>
                              log.warning("Unable to access shutdown hooks; they will not be run until the JVM exits")
                          }

                          // A couple notes on threads:
                          // 1) We rely on a convention that Akka follows for naming its threads, that is
                          //    they start with "${ActorSystemName}-"
                          // 2) If this convention changes, we may be waiting on threads that our our own
                          //    (i.e. belong to landlordd) and the process will never exit.

                          // A couple notes on shutdown hooks:
                          // 1) We don't interrupt shutdown hook threads -- this behavior is
                          // consistent with the JVM which doesn't interrupt them either.
                          // 2) It's possible that a shutdown hook registered its own hook. In that
                          // case, they will not be executed until landlordd itself is shutdown.

                          @annotation.tailrec
                          def waitForTermination(): Unit =
                            activeThreads(group).find(!_.getName.startsWith(context.system.name)) match {
                              case Some(h) =>
                                try {
                                  h.join()
                                } catch { case _: InterruptedException => }

                                waitForTermination()
                              case _ =>
                            }

                          waitForTermination()

                          log.debug("All threads in group {} have terminated, cleaning up", group.getName)

                          exitStatusPromise.success(status)

                          try {
                            Thread.sleep(outputDrainTimeAtExit.toMillis)
                          } catch { case _: InterruptedException => }

                          stdinIs.close()
                          stdoutPos.close()
                          stderrPos.close()
                          classLoaderWeakRef.get.foreach(_.close())
                          stdin.destroy()
                          stdout.destroy()
                          stderr.destroy()
                          properties.destroy()
                          securityManager.destroy()

                        }, s"${context.system.name}-cleanup").start()
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

                  val props = new Properties()
                  (StandardPropNames ++ exposedPropNames).foreach { exposedPropName =>
                    val exposedPropValue = properties.fallback.getProperty(exposedPropName)
                    if (exposedPropValue != null) props.setProperty(exposedPropName, exposedPropValue)
                  }
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
                      if (useDefaultSecurityManager) {
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
                    .keepAlive(heatbeatInterval, () => StdoutPrefix ++ sizeToBytes(0))
                    .concat(
                      Source.fromFuture(
                        exitStatusPromise
                          .future
                          .map(exitStatusToBytes)
                      )
                    )
                )
                .watchTermination() {
                  case (m, done) =>
                    done.onComplete { _ =>
                      self ! ConnectionWriteClosed
                    }

                    m
                }
            )

            processThread.setContextClassLoader(classLoader)
            processThread.start()

            context.become(started(cls, processThreadGroup, stopInProgress))
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
          .watchTermination() {
            case (m, done) =>
              done.onComplete { _ =>
                self ! Stop
              }

              m
          }
      )
  }

  def started(mainClass: Class[_], processThreadGroup: ThreadGroup, stopInProgress: AtomicBoolean): Receive = {
    case SignalProcess(signal) =>
      if (!stopInProgress.get && !processThreadGroup.isDestroyed) { // Best effort
        try {
          new Thread(
            processThreadGroup, { () =>
            try {
              val signalMeth = mainClass.getMethod("trap", Integer.TYPE)
              signalMeth.invoke(null, signal.asInstanceOf[Object])
            } catch {
              case _: NoSuchMethodException =>
            }
          }: Runnable, s"${context.system.name}-trap"
          ).start()
          if (signal == SIGABRT || signal == SIGINT || signal == SIGTERM)
            timers.startSingleTimer("signalCheck", Stop, exitTimeout)
        } catch {
          case NonFatal(_) => // There's still a chance that starting the trap thread can fail
        }
      }

    case ConnectionReadClosed =>
      try {
        new Thread(
          processThreadGroup, { () => stdin.signalClose() }, s"${context.system.name}-eot"
        ).start()
      } catch {
        case NonFatal(_) => // All we can do is try
      }

    case ConnectionWriteClosed =>
      if (stopInProgress.get) {
        self ! Stop
      } else {
        self ! SignalProcess(SIGINT)
      }

    case Stop =>
      val remainingThreads = activeThreads(processThreadGroup).filterNot(_.getName.startsWith(context.system.name))
      val remainingThreadCount = remainingThreads.size
      if (remainingThreadCount > 0)
        log.error(
          "RESOURCE RETENTION - Process {} has {} thread(s) still running - check that your shutdown hooks and/or `trap` handler is shutting down all that it needs to.\nRemaining threads: {}",
          processId, remainingThreadCount, remainingThreads.map(_.getName).mkString(", "))
      if (!stopInProgress.get())
        log.error(
          "RESOURCE RETENTION - Process {} has not called `System.exit` after {}.\nNote when using `trap`, `System.exit` must get called, even with 0, so that the process can be unloaded.",
          processId, exitTimeout)
      context.stop(self)
  }

  override def postStop(): Unit = {
    log.debug("Process actor stopped for {} - removing its working dir at {}", processId, processDirPath)
    Future(deleteFiles(processDirPath))(blockingDispatcher)
  }
}
