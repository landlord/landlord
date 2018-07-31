package com.github.huntc.landlord

import java.io.ByteArrayOutputStream
import java.nio.file.Files

import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveOutputStream }
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
class TarStreamWriterPerf {

  private var bytesSource = Source.empty[ByteString]

  private val rootPath = Files.createTempDirectory("benchmarks")

  private implicit val system: ActorSystem = ActorSystem()
  private implicit val mat: Materializer = ActorMaterializer()

  @Setup
  def setup(): Unit = {
    val bos = new ByteArrayOutputStream()
    val tos =
      new ArchiveStreamFactory()
        .createArchiveOutputStream(ArchiveStreamFactory.TAR, bos)
        .asInstanceOf[TarArchiveOutputStream]
    try {
      for (i <- 0 until 3000) {
        {
          val te = new TarArchiveEntry(s"dir$i/")
          tos.putArchiveEntry(te)
          tos.closeArchiveEntry()
        }
        {
          val te = new TarArchiveEntry(s"dir$i/foo")
          val data = "some-content".getBytes("UTF-8")
          te.setSize(data.length.toLong)
          tos.putArchiveEntry(te)
          tos.write(data)
          tos.closeArchiveEntry()
        }
      }
      tos.flush()
      tos.finish()
    } finally {
      tos.close()
    }
    val bytes = ByteString(bos.toByteArray)

    bytesSource = Source.single(bytes)

    rootPath.toFile.deleteOnExit()
  }

  @Benchmark
  def testWriter(): Unit = {
    val blockingEc = scala.concurrent.ExecutionContext.Implicits.global
    Await.result(TarStreamWriter.writeTarStream(bytesSource, rootPath, blockingEc), 10.seconds)
  }

  @TearDown
  def tearDown(): Unit = {
    system.terminate()
  }
}
