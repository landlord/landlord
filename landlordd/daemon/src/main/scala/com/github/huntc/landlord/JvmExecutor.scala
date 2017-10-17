package com.github.huntc.landlord

import akka.NotUsed
import akka.actor.{ Actor, PoisonPill, Props }
import akka.contrib.process.NonBlockingProcess
import akka.pattern.pipe
import akka.util.ByteString
import akka.stream.{ ActorMaterializer, AbruptStageTerminationException, Attributes, FlowShape, Inlet, Outlet, OverflowStrategy, QueueOfferResult }
import akka.stream.stage.{ AsyncCallback, GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.scaladsl.{ Keep, Sink, Source, SourceQueueWithComplete }
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.nio.file.{ Path, Paths }
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.Success

object JvmExecutor {
  def props(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]], bootstrapLibPath: Path, processDirPath: Path): Props =
    Props(new JvmExecutor(in, out, bootstrapLibPath, processDirPath))

  case class StartProcess(commandLine: String, stdin: Source[ByteString, AnyRef])
  case class StopProcess(signal: Int)

  private[landlord] sealed abstract class ProcessInputPart
  private[landlord] case class CommandLine(value: String) extends ProcessInputPart
  private[landlord] case class Archive(value: Source[ByteString, AnyRef]) extends ProcessInputPart
  private[landlord] case class Stdin(value: Source[ByteString, AnyRef]) extends ProcessInputPart
  private[landlord] case class Signal(value: Int) extends ProcessInputPart

  private[landlord] class ProcessParameterParser(implicit mat: ActorMaterializer, ec: ExecutionContext)
    extends GraphStage[FlowShape[ByteString, ProcessInputPart]] {

    val in = Inlet[ByteString]("ProcessParameters.in")
    val out = Outlet[ProcessInputPart]("ProcessParameters.out")

    override val shape = FlowShape.of(in, out)

    override def createLogic(attr: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {

        val asyncTryPull = getAsyncCallback[Unit](_ => if (!hasBeenPulled(in)) tryPull(in))
        val asyncCancel = getAsyncCallback[Unit](_ => cancel(in))

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
            right.drop(1)
          }
        }

        def becomeReceiveTar(): Unit = {
          val (queue, ar) =
            Source
              .queue[ByteString](100, OverflowStrategy.backpressure)
              .prefixAndTail(0)
              .map {
                case (_, in) => in
              }
              .toMat(Sink.head)(Keep.both)
              .run
          emit(out, Archive(Source.fromFutureSource(ar)))
          become(receiveTar(asyncTryPull, asyncCancel, queue, ByteString.empty))
        }

        def receiveTar(
          asyncTryPull: AsyncCallback[Unit], asyncCancel: AsyncCallback[Unit],
          queue: SourceQueueWithComplete[ByteString], recordBuffer: ByteString)(bytes: ByteString): ByteString = {
          val Blocksize = 512
          val Eotsize = Blocksize * 2
          val BlockingFactor = 20
          val RecordSize = Blocksize * BlockingFactor
          val remaining = RecordSize - recordBuffer.size
          val (add, carry) = bytes.splitAt(remaining)
          val enqueueRecordBuffer = recordBuffer ++ add
          val nonEnqueuedRecordBuffer =
            if (enqueueRecordBuffer.size == RecordSize) {
              queue.offer(enqueueRecordBuffer).andThen {
                case Success(QueueOfferResult.Enqueued) => asyncTryPull.invoke(())
                case _                                  => asyncCancel.invoke(())
              }
              ByteString.empty
            } else if (carry.isEmpty && !hasBeenPulled(in)) {
              tryPull(in)
              enqueueRecordBuffer
            } else {
              enqueueRecordBuffer
            }
          if (enqueueRecordBuffer.takeRight(Eotsize).forall(_ == 0)) {
            queue.complete()
            becomeReceiveStdin()
          } else {
            become(receiveTar(asyncTryPull, asyncCancel, queue, nonEnqueuedRecordBuffer))
          }
          carry
        }

        def becomeReceiveStdin(): Unit = {
          val (queue, stdin) =
            Source
              .queue[ByteString](100, OverflowStrategy.backpressure)
              .prefixAndTail(0)
              .map {
                case (_, in) => in
              }
              .toMat(Sink.head)(Keep.both)
              .run
          emit(out, Stdin(Source.fromFutureSource(stdin)))
          become(receiveStdin(asyncTryPull, asyncCancel, queue))
        }

        def receiveStdin(
          asyncTryPull: AsyncCallback[Unit], asyncCancel: AsyncCallback[Unit],
          queue: SourceQueueWithComplete[ByteString])(bytes: ByteString): ByteString = {
          val eotPosn = bytes.indexOf(4)
          val (left, right) = if (eotPosn == -1) bytes -> ByteString.empty else bytes.splitAt(eotPosn)
          queue.offer(left).andThen {
            case Success(QueueOfferResult.Enqueued) => asyncTryPull.invoke(())
            case _                                  => asyncCancel.invoke(())
          }
          if (eotPosn > -1) {
            queue.complete()
            become(receiveSignal(ByteString.empty))
          } else {
            become(receiveStdin(asyncTryPull, asyncCancel, queue))
          }
          right.drop(1)
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
          }
          if (carry.isEmpty) pull(in)
          carry
        }

        def receiveFinished()(bytes: ByteString): ByteString =
          receive(ByteString.empty)

        def become(receiver: ByteString => ByteString): Unit =
          receive = receiver
        var receive: ByteString => ByteString = receiveCommandLine(new StringBuilder)

        setHandler(in, new InHandler {
          override def onPush(): Unit = {
            @tailrec def doReceive(bytes: ByteString): ByteString =
              if (bytes.nonEmpty) doReceive(receive(bytes)) else bytes
            doReceive(grab(in))
          }
        })

        setHandler(out, new OutHandler {
          override def onPull(): Unit = {
            if (!hasBeenPulled(in)) pull(in)
          }
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
      cp: String = "",
      mainClass: String = ""
  )

  private[landlord] val parser = new scopt.OptionParser[JavaConfig](Version.executableScriptName) {
    opt[String]("classpath").abbr("cp").action { (x, c) =>
      c.copy(cp = x)
    }

    arg[String]("main class").action { (x, c) =>
      c.copy(mainClass = x)
    }
  }

  private[landlord] case class ExitEarly(exitStatus: Int, errorMessage: Option[String])

  private[landlord] val SIGINT = 2
  private[landlord] val SIGKILL = 9
  private[landlord] val SIGTERM = 15

  private[landlord] def sizeToBytes(size: Int): ByteString =
    ByteString.newBuilder.putInt(size)(ByteOrder.BIG_ENDIAN).result()

  private[landlord] def exitStatusToBytes(statusCode: Int): ByteString =
    ByteString.newBuilder.putByte('x').putInt(statusCode)(ByteOrder.BIG_ENDIAN).result()

  private[landlord] val StdoutPrefix = ByteString('o'.toByte)
  private[landlord] val StderrPrefix = ByteString('e'.toByte)
}

/**
 * A JVM Executor creates a JVM process and is also responsible for terminating it.
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
class JvmExecutor(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]], bootstrapLibPath: Path, processDirPath: Path) extends Actor {

  import JvmExecutor._

  implicit val mat = ActorMaterializer()
  import context.dispatcher

  def stopSelfWhenDone(mat: NotUsed, done: Future[akka.Done]): NotUsed = {
    done.map(_ => PoisonPill).pipeTo(self)
    NotUsed
  }

  override def preStart: Unit =
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
          self ! StopProcess(value)
          Future.successful(cl)
      }
      .recover {
        case e: AbruptStageTerminationException =>
          // Swallow it up - it is quite normal that our process exits with this stream
          // still active
          throw e
        case NonFatal(e) =>
          self ! ExitEarly(1, Some(e.getMessage))
          throw e
      }

  def receive: Receive =
    starting(unstopped = true)

  def starting(unstopped: Boolean): Receive = {
    case StartProcess(commandLine, stdinSource) if unstopped =>
      val javaCommand = Paths.get(sys.props.get("java.home").getOrElse("/usr") + "/bin/java").toString
      val errCapture = new BoundedByteArrayOutputStream
      Console.withErr(errCapture) {
        parser.parse(commandLine.split(" "), JavaConfig())
      } match {
        case Some(javaConfig) =>
          val args =
            List(
              javaCommand,
              "-cp", bootstrapLibPath.toAbsolutePath.toString + (if (javaConfig.cp.nonEmpty) ":" + javaConfig.cp else ""),
              "com.github.huntc.landlord.Main", // Launch via our bootstrap class so that we get a look-in to do stuff.
              javaConfig.mainClass
            )
          context.actorOf(NonBlockingProcess.props(args, workingDir = processDirPath.toAbsolutePath))
          context.become(started(stdinSource, Promise[Int]()))
        case None =>
          self ! ExitEarly(1, Some(errCapture.toString))
      }

    case _: StartProcess =>
      self ! ExitEarly(128 + SIGINT, None)

    case _: StopProcess =>
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

  def started(stdinSource: Source[ByteString, AnyRef], exitStatusPromise: Promise[Int]): Receive = {
    case NonBlockingProcess.Started(pid, stdin, stdout, stderr) =>
      stdinSource.runWith(stdin)
      out.success(
        stdout
          .map(bytes => StdoutPrefix ++ sizeToBytes(bytes.size) ++ bytes)
          .merge(stderr.map(bytes => StderrPrefix ++ sizeToBytes(bytes.size) ++ bytes))
          .concat(Source.fromFuture(exitStatusPromise.future.map(exitStatusToBytes)))
          .watchTermination()(stopSelfWhenDone))

    case StopProcess(signal) =>
      val process = context.children.headOption
      signal match {
        case SIGTERM => process.foreach(_ ! NonBlockingProcess.Destroy)
        case SIGKILL => process.foreach(_ ! NonBlockingProcess.DestroyForcibly)
        case other   => sys.error(s"Unsupported signal: $other. Ignoring.")
      }

    case NonBlockingProcess.Exited(status) =>
      exitStatusPromise.success(status)
  }
}