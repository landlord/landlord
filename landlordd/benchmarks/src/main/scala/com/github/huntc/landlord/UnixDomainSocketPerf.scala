package com.github.huntc.landlord

import java.io.File
import java.nio.file.Files

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.unixdomainsocket.scaladsl.UnixDomainSocket
import akka.stream.scaladsl.{ Flow, Sink, Source, Tcp }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.util.ByteString
import jnr.unixsocket.UnixSocketAddress
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

object UnixDomainSocketPerf {
  def main(args: Array[String]): Unit = {
    val perf = new UnixDomainSocketPerf
    perf.setup()
    perf.testUnixDomainSocket()
    perf.tearDown()
  }

  val serverFlow: Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].dropWhile(_ => true)
}

@State(Scope.Benchmark)
class UnixDomainSocketPerf {

  import UnixDomainSocketPerf._

  private var bytesSource = Source.empty[ByteString]

  private var unixDomainSocketFile: File = _

  private implicit val system: ActorSystem = ActorSystem()
  private implicit val mat: Materializer = ActorMaterializer()

  @Setup
  def setup(): Unit = {

    val bytes = ByteString(Array.ofDim[Byte](100000000))

    bytesSource = Source.single(bytes)

    unixDomainSocketFile = Files.createTempFile("UnixDomainSocketPerf", ".sock").toFile
    unixDomainSocketFile.delete()
    unixDomainSocketFile.deleteOnExit()
  }

  @Benchmark
  def testUnixDomainSocket(): Unit = {
    val binding = UnixDomainSocket().bindAndHandle(serverFlow, unixDomainSocketFile)

    import scala.concurrent.ExecutionContext.Implicits.global
    val result = binding.flatMap { connection =>
      bytesSource
        .via(UnixDomainSocket().outgoingConnection(new UnixSocketAddress(unixDomainSocketFile)))
        .runWith(Sink.ignore)
        .flatMap(_ => connection.unbind())
    }
    Await.result(result, 10.seconds)
  }

  @Benchmark
  def testTcpSocket(): Unit = {
    val binding = Tcp().bindAndHandle(serverFlow, "localhost", 1111)

    import scala.concurrent.ExecutionContext.Implicits.global
    val result = binding.flatMap { connection =>
      bytesSource
        .via(Tcp().outgoingConnection("localhost", 1111))
        .runWith(Sink.ignore)
        .flatMap(_ => connection.unbind())
    }
    Await.result(result, 10.seconds)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
