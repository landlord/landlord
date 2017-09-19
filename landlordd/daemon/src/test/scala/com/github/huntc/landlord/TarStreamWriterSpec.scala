package com.github.huntc.landlord

import akka.util.ByteString
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.{ ArchiveException, ArchiveStreamFactory }
import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveOutputStream }
import org.scalatest._

class TarStreamWriterSpec extends TestKit(ActorSystem("TarStreamWriterSpec"))
  with AsyncWordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "The TarStreamWriter" should {
    "Write out a valid stream of Tar input" in {
      implicit val mat = ActorMaterializer()
      val rootPath = Files.createTempDirectory("TarStreamWriterSpec")
      rootPath.toFile.deleteOnExit()
      val source =
        Source.single {
          val bos = new ByteArrayOutputStream()
          val tos =
            new ArchiveStreamFactory()
              .createArchiveOutputStream(ArchiveStreamFactory.TAR, bos)
              .asInstanceOf[TarArchiveOutputStream]
          try {
            {
              val te = new TarArchiveEntry("dir/")
              tos.putArchiveEntry(te)
              tos.closeArchiveEntry()
            }
            {
              val te = new TarArchiveEntry("dir/foo")
              val data = "some-content".getBytes("UTF-8")
              te.setSize(data.length.toLong)
              tos.putArchiveEntry(te)
              tos.write(data)
              tos.closeArchiveEntry()
            }
            tos.flush()
            tos.finish()
          } finally {
            tos.close()
          }
          ByteString(bos.toByteArray)
        }
      val blockingEc = scala.concurrent.ExecutionContext.Implicits.global
      TarStreamWriter.writeTarStream(source, rootPath, blockingEc).map { _ =>
        val file = rootPath.resolve("dir/foo")
        assert(Files.size(file) == 12)
      }
    }

    "Reject an invalid stream of Tar input by failing the future" in {
      implicit val mat = ActorMaterializer()
      val rootPath = Files.createTempDirectory("TarStreamWriterSpec")
      rootPath.toFile.deleteOnExit()
      val source = Source.single(ByteString.empty)
      val blockingEc = scala.concurrent.ExecutionContext.Implicits.global
      recoverToSucceededIf[ArchiveException] {
        TarStreamWriter.writeTarStream(source, rootPath, blockingEc)
      }
    }
  }
}
