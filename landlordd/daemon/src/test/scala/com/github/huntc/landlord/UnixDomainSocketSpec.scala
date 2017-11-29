package com.github.huntc.landlord

import java.io.{ File, IOException }
import java.nio.file.Files

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.testkit._
import akka.util.ByteString
import org.scalatest._

class UnixDomainSocketSpec extends TestKit(ActorSystem("UnixDomainSocketSpec"))
  with AsyncWordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val ma: ActorMaterializer = ActorMaterializer()

  "A Unix Domain Socket" should {
    "receive what is sent" in {
      val file = Files.createTempFile("UnixDomainSocketSpec1", ".sock").toFile
      file.delete()
      file.deleteOnExit()

      val binding =
        UnixDomainSocket().bindAndHandle(Flow.fromFunction(identity), file)

      binding.flatMap { connection =>
        val sendBytes = ByteString("Hello")
        val result =
          Source.single(sendBytes)
            .via(UnixDomainSocket().outgoingConnection(file))
            .runWith(Sink.head)
        result
          .map(receiveBytes => assert(receiveBytes == sendBytes))
          .flatMap {
            case `succeed`       => connection.unbind().map(_ => succeed)
            case failedAssertion => failedAssertion
          }
      }
    }

    "not be able to bind to a non-existant file" in {
      val binding =
        UnixDomainSocket().bindAndHandle(Flow.fromFunction(identity), new File("/thisshouldnotexist"))

      binding.failed.map {
        case _: IOException => succeed
      }
    }

    "not be able to connect to a non-existant file" in {
      val connection =
        Source.single(ByteString("hi"))
          .via(UnixDomainSocket().outgoingConnection(new File("/thisshouldnotexist")))
          .runWith(Sink.head)

      connection.failed.map {
        case _: IOException => succeed
      }
    }
  }
}
