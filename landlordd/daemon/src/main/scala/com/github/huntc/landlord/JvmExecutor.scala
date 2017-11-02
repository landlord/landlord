package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.{ Actor, ActorLogging, PoisonPill, Props }
import akka.pattern.pipe
import akka.util.ByteString
import akka.stream._
import akka.stream.stage._
import akka.stream.scaladsl.{ BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete, StreamConverters }
import java.io.{ ByteArrayOutputStream, PrintStream }
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.ByteOrder
import java.nio.file.{ Path, Paths }
import java.security.Permission
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Properties

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration.FiniteDuration
import scala.ref.WeakReference
import scala.util.control.NonFatal
import scala.util.Success

object JvmExecutor {
  def props(
    processId: String,
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean, preventShutdownHooks: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    outputDrainTimeAtExit: FiniteDuration,
    processDirPath: Path
  ): Props =
    Props(
      new JvmExecutor(
        processId,
        properties, securityManager, useDefaultSecurityManager, preventShutdownHooks,
        stdin, stdinTimeout, stdout, stderr,
        in, out,
        outputDrainTimeAtExit,
        processDirPath
      )
    )

  case class StartProcess(commandLine: String, stdin: Source[ByteString, AnyRef])
  case class SignalProcess(signal: Int)

  private[landlord] sealed abstract class ProcessInputPart
  private[landlord] case class CommandLine(value: String) extends ProcessInputPart
  private[landlord] case class Archive(value: Source[ByteString, AnyRef]) extends ProcessInputPart
  private[landlord] case class Stdin(value: Source[ByteString, AnyRef]) extends ProcessInputPart
  private[landlord] case class Signal(value: Int) extends ProcessInputPart

  private[landlord] class ProcessParameterParser(implicit mat: ActorMaterializer, ec: ExecutionContext)
    extends GraphStage[FlowShape[ByteString, ProcessInputPart]] {

    private val in = Inlet[ByteString]("ProcessParameters.in")
    private val out = Outlet[ProcessInputPart]("ProcessParameters.out")

    override val shape: FlowShape[ByteString, ProcessInputPart] = FlowShape.of(in, out)

    override def createLogic(attr: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        def receiveCommandLine(commandLine: StringBuilder)(bytes: ByteString): ByteString = {
          val posn = bytes.indexOf('\n')
          val (left, right) = bytes.splitAt(posn)
          if (left.isEmpty) {
            commandLine ++= right.utf8String
            pull(in)
            ByteString.empty
          } else {
            commandLine ++= left.utf8String
            emit(out, CommandLine(commandLine.toString))
            becomeReceiveTar()
            val carry = right.drop(1)
            if (carry.nonEmpty)
              asyncReceive.invoke(())
            else
              pull(in)
            carry
          }
        }

        def becomeReceiveTar(): Unit = {
          val (queue, ar) =
            Source
              .queue[ByteString](100, OverflowStrategy.backpressure)
              .prefixAndTail(0)
              .map {
                case (_, arIn) => arIn
              }
              .toMat(Sink.head)(Keep.both)
              .run
          emit(out, Archive(Source.fromFutureSource(ar)))
          become(receiveTar(queue, ByteString.empty))
        }

        private val Blocksize = 512
        private val Eotsize = Blocksize * 2
        private val BlockingFactor = 20
        private val RecordSize = Blocksize * BlockingFactor
        assert(RecordSize >= Eotsize)

        def receiveTar(
          queue: SourceQueueWithComplete[ByteString],
          recordBuffer: ByteString)(bytes: ByteString): ByteString = {

          val remaining = RecordSize - recordBuffer.size
          val (add, carry) = bytes.splitAt(remaining)
          val enqueueRecordBuffer = recordBuffer ++ add
          if (enqueueRecordBuffer.size == RecordSize) {
            val enqueued = new AtomicBoolean(false)
            queue.offer(enqueueRecordBuffer).andThen {
              case Success(QueueOfferResult.Enqueued) =>
                enqueued.compareAndSet(false, true)
                asyncReceive.invoke(())
              case _ =>
                asyncCancel.invoke(())
            }
            become(receiveTarQueuePending(queue, enqueueRecordBuffer, enqueued))
          } else {
            if (!hasBeenPulled(in)) pull(in)
            become(receiveTar(queue, enqueueRecordBuffer))
          }
          carry
        }

        def receiveTarQueuePending(
          queue: SourceQueueWithComplete[ByteString],
          recordBuffer: ByteString,
          enqueued: AtomicBoolean)(bytes: ByteString): ByteString = {

          if (enqueued.get()) {
            if (recordBuffer.takeRight(Eotsize).forall(_ == 0)) {
              queue.complete()
              becomeReceiveStdin()
            } else {
              become(receiveTar(queue, ByteString.empty))
            }
            receive(bytes)
          } else
            bytes
        }

        def becomeReceiveStdin(): Unit = {
          val (queue, stdin) =
            Source
              .queue[ByteString](100, OverflowStrategy.backpressure)
              .prefixAndTail(0)
              .map {
                case (_, stdinIn) => stdinIn
              }
              .toMat(Sink.head)(Keep.both)
              .run
          emit(out, Stdin(Source.fromFutureSource(stdin)))
          become(receiveStdin(queue))
        }

        def receiveStdin(queue: SourceQueueWithComplete[ByteString])(bytes: ByteString): ByteString = {
          val eotPosn = bytes.indexOf(4)
          val (left, right) = if (eotPosn == -1) bytes -> ByteString.empty else bytes.splitAt(eotPosn)
          if (left.nonEmpty) {
            val enqueued = new AtomicBoolean(false)
            queue.offer(left).andThen {
              case Success(QueueOfferResult.Enqueued) =>
                enqueued.compareAndSet(false, true)
                asyncReceive.invoke(())
              case _ =>
                asyncCancel.invoke(())
            }
            become(receiveStdinQueuePending(queue, eotPosn, enqueued))
          }
          val carry = right.drop(1)
          if (carry.nonEmpty)
            asyncReceive.invoke(())
          else if (!hasBeenPulled(in))
            pull(in)
          carry
        }

        def receiveStdinQueuePending(
          queue: SourceQueueWithComplete[ByteString],
          eotPosn: Int,
          enqueued: AtomicBoolean)(bytes: ByteString): ByteString = {

          if (enqueued.get()) {
            if (eotPosn > -1) {
              queue.complete()
              become(receiveSignal(ByteString.empty))
            } else {
              become(receiveStdin(queue))
            }
            receive(bytes)
          } else
            bytes
        }

        def receiveSignal(recordBuffer: ByteString)(bytes: ByteString): ByteString = {
          val RecordSize = 4
          val remaining = RecordSize - recordBuffer.size
          val (add, carry) = bytes.splitAt(remaining)
          val newRecordBuffer = recordBuffer ++ add
          if (newRecordBuffer.size == RecordSize) {
            emit(out, Signal(newRecordBuffer.iterator.getInt(ByteOrder.BIG_ENDIAN)))
            become(receiveFinished())
          } else {
            become(receiveSignal(newRecordBuffer))
            if (!hasBeenPulled(in)) pull(in)
          }
          carry
        }

        def receiveFinished()(bytes: ByteString): ByteString =
          receive(ByteString.empty)

        def become(receiver: ByteString => ByteString): Unit =
          receive = receiver
        private var receive: ByteString => ByteString = receiveCommandLine(new StringBuilder)
        private var carry: ByteString = ByteString.empty

        private var asyncReceive: AsyncCallback[Unit] = _
        private var asyncCancel: AsyncCallback[Unit] = _

        override def preStart(): Unit = {
          asyncReceive = getAsyncCallback[Unit](_ => carry = receive(carry))
          asyncCancel = getAsyncCallback[Unit](_ => cancel(in))
          pull(in)
        }

        setHandler(in, new InHandler {
          override def onPush(): Unit =
            carry = receive(carry ++ grab(in))
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit =
            if (!hasBeenPulled(in)) pull(in)
        })
      }
  }

  private[landlord] class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    val MaxOutputSize = 8192

    override def write(c: Int): Unit =
      if (count + 1 < MaxOutputSize) super.write(c)

    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      if (count + (len - off) < MaxOutputSize) super.write(b, off, len)
  }

  private[landlord] case class JavaConfig(
      cp: Seq[Path] = List.empty,
      mainClass: String = ""
  )

  private[landlord] val parser = new scopt.OptionParser[JavaConfig](Version.executableScriptName) {
    opt[String]("classpath").abbr("cp").action { (x, c) =>
      c.copy(cp = x.split(":").map(p => Paths.get(p)))
    }

    arg[String]("main class").action { (x, c) =>
      c.copy(mainClass = x)
    }
  }

  private[landlord] case class ExitEarly(exitStatus: Int, errorMessage: Option[String])

  private[landlord] val SIGINT = 2
  private[landlord] val SIGTERM = 15

  private[landlord] def sizeToBytes(size: Int): ByteString =
    ByteString.newBuilder.putInt(size)(ByteOrder.BIG_ENDIAN).result()

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
 * The executor is constructed with a stream through which `java` command line args are
 * received along with a tar filesystem to read from followed by stdin and a signal. An outward
 * stream is also provided for transmitting stdout, stderr and the process's final exit code.
 *
 * The following specifications assume big-endian byte order.
 *
 * The incoming stream is presented as follows:
 *
 * 1. The first line (up until a LF) are the command line args to pass to the `java` command.
 *    The arguments are decoded as UTF-8.
 * 2. The next line represents the binary tar file output of the file system that the `java`
 *    command and its host program will ultimately read from e.g. containing the class files.
 * 3. The stream then represents stdin until an EOT is received. The input is decoded as UTF-8.
 * 4. The remaining stream will consist of a four byte integer representing a signal code
 *    and which will also terminate the stream.
 *
 * The outgoing stream is presented as follows:
 *
 * 1. A single UTF-8 character representing one of 'o', 'e' or 'x' (stdout, stderr, exit code).
 *
 * In the case of 'o' or 'e' then there is a four byte integer providing the length of the
 * UTF-8 characters to follow.
 *
 * Exit codes are conveyed as a four byte integer and are followed by the stream being
 * terminated. All stdout and stderr is guaranteed to be sent prior to the exit code being
 * transmitted.
 */
class JvmExecutor(
    processId: String,
    properties: ThreadGroupProperties, securityManager: ThreadGroupSecurityManager, useDefaultSecurityManager: Boolean, preventShutdownHooks: Boolean,
    stdin: ThreadGroupInputStream, stdinTimeout: FiniteDuration, stdout: ThreadGroupPrintStream, stderr: ThreadGroupPrintStream,
    in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]],
    outputDrainTimeAtExit: FiniteDuration,
    processDirPath: Path
) extends Actor with ActorLogging {

  import JvmExecutor._

  implicit val mat: ActorMaterializer = ActorMaterializer()
  import context.dispatcher

  def stopSelfWhenDone(mat: NotUsed, done: Future[akka.Done]): NotUsed = {
    done.map(_ => PoisonPill).pipeTo(self)
    NotUsed
  }

  override def preStart: Unit = {
    log.debug("Process actor starting for {}", processId)
    in
      .via(new ProcessParameterParser)
      .runFoldAsync("") {
        case (_, CommandLine(value)) =>
          Future.successful(value)
        case (cl, Archive(value)) =>
          TarStreamWriter
            .writeTarStream(
              value,
              processDirPath,
              context.system.dispatchers.lookup("akka.actor.default-blocking-io-dispatcher")
            )
            .map(_ => cl)
        case (cl, Stdin(value)) =>
          self ! StartProcess(cl, value)
          Future.successful(cl)
        case (cl, Signal(value)) =>
          self ! SignalProcess(value)
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
        case _ => self ! SignalProcess(SIGINT)
      }
  }

  def receive: Receive =
    starting(unstopped = true)

  def starting(unstopped: Boolean): Receive = {
    case StartProcess(commandLine, stdinSource) if unstopped =>
      val errCapture = new BoundedByteArrayOutputStream
      val commandLineArgs = commandLine.split(" ")
      Console.withErr(errCapture) {
        parser.parse(commandLineArgs, JavaConfig())
      } match {
        case Some(javaConfig) =>
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
          val classpath = javaConfig.cp.map(cp => processDirPath.resolve(cp).toUri.toURL)
          val classLoader = new URLClassLoader(classpath.toArray, this.getClass.getClassLoader.getParent)
          val classLoaderWeakRef = new WeakReference(classLoader)
          try {
            val cls = classLoader.loadClass(javaConfig.mainClass)
            val mainArgs = commandLineArgs.tail
            val meth = cls.getMethod("main", mainArgs.getClass)

            // Launch our "process"
            val stopped = new AtomicBoolean(false)
            val processThreadGroup =
              new ThreadGroup("process-" + processId) {
                override def uncaughtException(t: Thread, e: Throwable): Unit =
                  if (stopped.compareAndSet(false, true)) {
                    val status = e match {
                      case ite: InvocationTargetException =>
                        ite.getCause match {
                          case ExitException(s) =>
                            s // It is normal for this exception to occur given that we want the process to explicitly exit
                          case null =>
                            System.err.println("An invocation error cause is unexpectedly null. Stacktrace follows.")
                            ite.printStackTrace() // Shouldn't happen in the context of an invocation error.
                            1
                          case otherCause =>
                            otherCause.printStackTrace() // General uncaught errors within the process.
                            1
                        }
                      case otherException =>
                        System.err.println("An internal error has occurred within landlord. Stacktrace follows.")
                        otherException.printStackTrace()
                        70 // EXIT_SOFTWARE, Internal Software Error as defined in BSD sysexits.h
                    }
                    stdin.destroy()
                    stdout.destroy()
                    stderr.destroy()
                    properties.destroy()
                    securityManager.destroy()
                    Thread.sleep(outputDrainTimeAtExit.toMillis)
                    stdoutPos.close()
                    stderrPos.close()
                    classLoaderWeakRef.get.foreach(_.close())
                    exitStatusPromise.success(status)
                  } else {
                    throw e // Forward any further exceptions once we've exited
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
                  properties.init(props)
                  securityManager.init(new SecurityManager() {
                    override def checkExit(status: Int): Unit =
                      throw ExitException(status) // This will be caught as an uncaught exception
                    override def checkPermission(perm: Permission): Unit = {
                      if (perm == ShutdownHooksPerm) {
                        val message = """|: Shutdown hooks are not applicable within landlord as many applications reside in the same JVM.
                                         |Declare a `public static void trap(int signal)` for trapping signals and catch `SecurityException`
                                         |around your shutdown hook code.""".stripMargin
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
                  meth.invoke(null, mainArgs.asInstanceOf[Object])
                }: Runnable
              )

            out.success(
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
                .watchTermination()(stopSelfWhenDone)
            )

            processThread.start()

            context.become(started(cls, processThreadGroup, exitStatusPromise))
          } catch {
            case NonFatal(e) =>
              classLoader.close()
              self ! ExitEarly(1, Some(if (e.getCause != null) e.getCause.toString else e.toString))
          }
        case None =>
          self ! ExitEarly(1, Some(errCapture.toString))
      }

    case _: StartProcess =>
      self ! ExitEarly(128 + SIGINT, None)

    case _: SignalProcess =>
      context.become(starting(unstopped = false))

    case ExitEarly(exitStatus, errorMessage) =>
      out.success(
        Source
          .single {
            errorMessage.fold(ByteString.empty)(e => StderrPrefix ++ ByteString(e)) ++
              exitStatusToBytes(exitStatus)
          }
          .watchTermination()(stopSelfWhenDone)
      )
  }

  def started(mainClass: Class[_], processThreadGroup: ThreadGroup, exitStatusPromise: Promise[Int]): Receive = {
    case SignalProcess(signal) =>
      if (!exitStatusPromise.isCompleted)
        new Thread(
          processThreadGroup,
          { () =>
            try {
              val signalMeth = mainClass.getMethod("trap", Integer.TYPE)
              signalMeth.invoke(null, signal.asInstanceOf[Object])
            } catch {
              case _: NoSuchMethodException =>
            }
          }: Runnable
        ).start()
  }

  override def postStop(): Unit =
    log.debug("Process actor stopped for {}", processId)
}