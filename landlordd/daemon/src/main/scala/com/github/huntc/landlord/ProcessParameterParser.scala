package com.github.huntc.landlord

import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

import akka.stream._
import akka.stream.scaladsl.{ Keep, Sink, Source, SourceQueueWithComplete }
import akka.stream.stage._
import akka.util.ByteString

import scala.concurrent.ExecutionContext
import scala.util.Success

object ProcessParameterParser {

  sealed abstract class ProcessInputPart

  case class CommandLine(value: String) extends ProcessInputPart

  case class Archive(value: Source[ByteString, AnyRef]) extends ProcessInputPart

  case class Stdin(value: Source[ByteString, AnyRef]) extends ProcessInputPart

  class UnexpectedEOS(suffix: String) extends RuntimeException("Unexpected end of stream while receiving " + suffix)
}

/**
 * This stage describes stream parsing through which `java` command line args are
 * received along with a tar filesystem to read from followed by stdin and a signal.
 *
 * The following specifications assume big-endian byte order.
 *
 * The stream is presented as follows:
 *
 * 1. The first line (up until a LF) are the command line args to pass to the `java` command.
 *    The arguments are decoded as UTF-8.
 * 2. The next line represents the binary tar file output of the file system that the `java`
 *    command and its host program will ultimately read from e.g. containing the class files.
 * 3. The stream then represents stdin until the stream is completed. The input is decoded as UTF-8.
 *
 */
class ProcessParameterParser(implicit mat: ActorMaterializer, ec: ExecutionContext)
  extends GraphStage[FlowShape[ByteString, ProcessParameterParser.ProcessInputPart]] {

  import ProcessParameterParser._

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
          if (!isClosed(in)) {
            if (!hasBeenPulled(in)) pull(in)
          } else {
            failStage(new UnexpectedEOS("archive"))
          }
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
        if (bytes.nonEmpty) {
          val enqueued = new AtomicBoolean(false)
          queue.offer(bytes).andThen {
            case Success(QueueOfferResult.Enqueued) =>
              enqueued.compareAndSet(false, true)
              asyncReceive.invoke(())
            case _ =>
              asyncCancel.invoke(())
          }
          become(receiveStdinQueuePending(queue, enqueued))
          if (!isClosed(in) && !hasBeenPulled(in)) pull(in)
        } else if (isClosed(in)) {
          completeStage()
          become(receiveFinished())
        }
        ByteString.empty
      }

      def receiveStdinQueuePending(
        queue: SourceQueueWithComplete[ByteString],
        enqueued: AtomicBoolean)(bytes: ByteString): ByteString = {

        if (enqueued.get()) {
          if (isClosed(in)) {
            queue.complete()
            completeStage()
            become(receiveFinished())
          } else {
            become(receiveStdin(queue))
          }
          receive(bytes)
        } else
          bytes
      }

      def receiveFinished()(bytes: ByteString): ByteString =
        ByteString.empty

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

        override def onUpstreamFinish(): Unit =
          asyncReceive.invoke(())
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          if (!isClosed(in) && !hasBeenPulled(in)) pull(in)
      })
    }
}

