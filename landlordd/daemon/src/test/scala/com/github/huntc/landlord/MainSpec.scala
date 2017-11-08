package com.github.huntc.landlord

import java.nio.ByteOrder

import akka.NotUsed
import akka.actor.{ Actor, ActorSelection, ActorSystem, Props }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.ActorMaterializer
import akka.testkit._
import akka.util.ByteString
import org.scalatest._

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

      Source
        .single(ByteString("lsomeinput"))
        .via(Main.controlFlow(launchInfoOp, emptySendKillOp))
        .runWith(Sink.head)
        .map(r => assert(r.utf8String == "someinput"))
    }

    "receive a process id and signal when killing a process" in {
      val result = Promise[Assertion]()
      def sendKillOp(jvmExecutor: ActorSelection, signal: Int): Unit =
        result.complete(Try(assert(jvmExecutor.pathString == "/user/123" && signal == 15)))

      Source
        .single(ByteString.newBuilder.putByte('k').putInts(Array(123, 15))(ByteOrder.BIG_ENDIAN).result())
        .via(Main.controlFlow(emptyLaunchInfoOp, sendKillOp))
        .runWith(Sink.ignore)

      result.future
    }

    "receive ??? with an unknown command" in {
      Source
        .single(ByteString.newBuilder.putByte('z').result())
        .via(Main.controlFlow(emptyLaunchInfoOp, emptySendKillOp))
        .runWith(Sink.head)
        .map(r => assert(r.utf8String == "???"))
    }
  }
}
