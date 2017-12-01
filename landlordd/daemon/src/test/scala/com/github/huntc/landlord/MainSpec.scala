package com.github.huntc.landlord

import java.nio.ByteOrder

import akka.{ Done, NotUsed }
import akka.actor.{ Actor, ActorSelection, ActorSystem, PoisonPill, Props }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.ActorMaterializer
import akka.testkit._
import akka.util.ByteString
import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.Promise
import scala.util.Try

class MainSpec extends TestKit(ActorSystem("MainSpec"))
  with AsyncWordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val ma: ActorMaterializer = ActorMaterializer()

  def emptyLaunchInfoOp(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]]): (Int, Props) =
    0 -> Props.empty

  def emptySendKillOp(jvmExecutor: ActorSelection, signal: Int): Unit =
    ()

  "Main" should {
    "process input for launching a process" in {
      val reaper = TestProbe()

      def launchInfoOp(in: Source[ByteString, NotUsed], out: Promise[Source[ByteString, NotUsed]]): (Int, Props) =
        123 -> Props(new Actor {
          override def preStart(): Unit = {
            in
              .runWith(Sink.head)
              .foreach(input => out.success(Source.single(input)))
          }
          def receive: Receive = {
            case _ =>
          }
        })

      val result =
        Source
          .single(ByteString("lsomeinput"))
          .via(Main.controlFlow(reaper.ref, launchInfoOp, emptySendKillOp))
          .runWith(Sink.head)

      reaper.expectMsgClass(classOf[Main.JvmExecutorReaper.Register])
      result.map(r => assert(r.utf8String == "someinput"))
    }

    "receive a process id and signal when killing a process" in {
      val reaper = TestProbe()

      val result = Promise[Assertion]()
      def sendKillOp(jvmExecutor: ActorSelection, signal: Int): Unit =
        result.complete(Try(assert(jvmExecutor.pathString == "/user/process-123" && signal == 15)))

      Source
        .single(ByteString.newBuilder.putByte('k').putInts(Array(123, 15))(ByteOrder.BIG_ENDIAN).result())
        .via(Main.controlFlow(reaper.ref, emptyLaunchInfoOp, sendKillOp))
        .runWith(Sink.ignore)

      reaper.expectNoMessage(1.second.dilated)
      result.future
    }

    "receive ??? with an unknown command" in {
      val reaper = TestProbe()

      val result =
        Source
          .single(ByteString.newBuilder.putByte('z').result())
          .via(Main.controlFlow(reaper.ref, emptyLaunchInfoOp, emptySendKillOp))
          .runWith(Sink.head)

      reaper.expectNoMessage(1.second.dilated)
      result.map(r => assert(r.utf8String == "???"))
    }
  }

  "JvmExecutorReaper" should {
    import Main.JvmExecutorReaper
    "register two actors and receive done with one having stopped and the other acting upon shutting down" in {
      val executor0 = TestProbe()
      val executor1 = TestProbe()
      val reaper = system.actorOf(JvmExecutorReaper.props)
      reaper ! JvmExecutorReaper.Register(executor0.ref)
      reaper ! JvmExecutorReaper.Register(executor1.ref)
      executor0.ref ! PoisonPill
      val shutdownRequestor = TestProbe()
      shutdownRequestor.send(reaper, JvmExecutorReaper.Shutdown)
      executor0.expectNoMessage(1.second.dilated)
      executor1.expectMsg(JvmExecutor.SignalProcess(JvmExecutor.SIGTERM))
      executor1.ref ! PoisonPill
      shutdownRequestor.expectMsg(Done)
      succeed
    }

    "don't register an actor but receive done upon shutting down" in {
      val reaper = system.actorOf(JvmExecutorReaper.props)
      val shutdownRequestor = TestProbe()
      shutdownRequestor.send(reaper, JvmExecutorReaper.Shutdown)
      shutdownRequestor.expectMsg(Done)
      succeed
    }
  }
}
