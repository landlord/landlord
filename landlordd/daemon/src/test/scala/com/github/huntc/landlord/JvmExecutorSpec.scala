package com.github.huntc.landlord

import akka.util.{ ByteString, ByteStringBuilder }
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit._
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.nio.file.{ Files, Paths }
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveOutputStream }
import org.scalatest._
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.concurrent.duration._

class JvmExecutorSpec extends TestKit(ActorSystem("JvmExecutorSpec"))
  with AsyncWordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val mat = ActorMaterializer()

  "The ProcessParameterParser" should {
    "produce a flow of ProcessInputParts in the required order given a valid input" in {
      val cl = "some args"

      val tar = {
        val bos = new ByteArrayOutputStream()
        val tos =
          new ArchiveStreamFactory()
            .createArchiveOutputStream(ArchiveStreamFactory.TAR, bos)
            .asInstanceOf[TarArchiveOutputStream]
        try {
          tos.flush()
          tos.finish()
        } finally {
          tos.close()
        }
        bos.toByteArray
      }

      val stdin = "some stdin\nsome more stdin\n"

      val signal = 15

      val TarRecordSize = 10240

      Source
        .single(
          ByteString(cl + "\n") ++
            ByteString(tar) ++
            ByteString(stdin + "\u0004") ++
            ByteString.newBuilder.putInt(signal)(ByteOrder.BIG_ENDIAN).result()
        )
        .via(new JvmExecutor.ProcessParameterParser)
        .runFoldAsync(0 -> assert(true)) {
          case ((ordinal, _), JvmExecutor.CommandLine(value)) =>
            Future.successful(1 -> assert(ordinal == 0 && value == cl))
          case ((ordinal, _), JvmExecutor.Archive(value)) =>
            val complete = value.runFold(0L)(_ + _.size)
            complete.map(tarSize => 2 -> assert(ordinal == 1 && tarSize == TarRecordSize))
          case ((ordinal, _), JvmExecutor.Stdin(value)) =>
            val complete = value.runFold("")(_ ++ _.utf8String)
            complete.map(input => 3 -> assert(ordinal == 2 && input == stdin))
          case ((ordinal, _), JvmExecutor.Signal(value)) =>
            Future.successful(4 -> assert(ordinal == 3 && value == signal))
        }
        .map {
          case (count, Succeeded) if count == 4 => Succeeded
          case (count, Succeeded)               => assert(count == 4)
          case (_, lastAssertion)               => lastAssertion
        }
    }
  }

  "The JVMExecutor" should {
    "start a process that then outputs stdin, ends and shuts everything down" in {
      val cl = "-cp classes example.Hello"

      val tar = {
        val bos = new ByteArrayOutputStream()
        val tos =
          new ArchiveStreamFactory()
            .createArchiveOutputStream(ArchiveStreamFactory.TAR, bos)
            .asInstanceOf[TarArchiveOutputStream]
        try {
          {
            val te = new TarArchiveEntry("classes/")
            tos.putArchiveEntry(te)
            tos.closeArchiveEntry()
          }
          {
            val te = new TarArchiveEntry("classes/example/")
            tos.putArchiveEntry(te)
            tos.closeArchiveEntry()
          }
          {
            val te = new TarArchiveEntry("classes/example/Hello.class")
            val classFile = Paths.get(getClass.getResource("/example/Hello.class").toURI)
            val data = Files.readAllBytes(classFile)
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
        bos.toByteArray
      }

      val stdin = "Hello World\n"

      val in =
        Source
          .single(
            ByteString(cl + "\n") ++
              ByteString(tar) ++
              ByteString(stdin + "\u0004")
          )

      val out = Promise[Source[ByteString, akka.NotUsed]]()
      val bootstrapLibPath = Paths.get(getClass.getResource("/bootstrap-assembly.jar").toURI)
      val processDirPath = Files.createTempDirectory("jvm-executor-spec")
      processDirPath.toFile.deleteOnExit

      val process = system.actorOf(JvmExecutor.props(in, out, bootstrapLibPath, processDirPath))

      val outputOk =
        out.future
          .flatMap { outSource =>
            outSource
              .runFold(ByteString.empty)(_ ++ _)
              .map { bytes =>
                val (_, _, _, byteStrings) = bytes.foldLeft((false, 0, 0, List.empty[ByteStringBuilder])) {
                  case ((false, 0, 0, byteStrings), byte) if byte == 'o'.toByte =>
                    (true, 0, 0, byteStrings)
                  case ((false, 0, 0, byteStrings), byte) if byte == 'x'.toByte =>
                    (false, 0, 4, byteStrings :+ ByteString.newBuilder)
                  case ((true, 0, 0, byteStrings), byte) =>
                    (true, 1, byte << 24, byteStrings)
                  case ((true, 1, size, byteStrings), byte) =>
                    (true, 2, size | (byte << 16), byteStrings)
                  case ((true, 2, size, byteStrings), byte) =>
                    (true, 3, size | (byte << 8), byteStrings)
                  case ((true, 3, size, byteStrings), byte) =>
                    (false, 0, size | byte, byteStrings :+ ByteString.newBuilder)
                  case ((false, 0, size, byteStrings), byte) if size > 0 =>
                    byteStrings.last.putByte(byte)
                    (false, 0, size - 1, byteStrings)
                }
                val stdoutBytes = byteStrings.dropRight(1).foldLeft(ByteString.empty)(_ ++ _.result)
                val exitCodeBytes = byteStrings.last.result
                assert(stdoutBytes.utf8String == stdin && exitCodeBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == 0)
              }
          }(ExecutionContext.Implicits.global) // We use this context to get us off the ScalaTest one (which would hang this)

      val watcher = TestProbe()
      watcher.watch(process)
      watcher.expectTerminated(process, max = 5.seconds.dilated)

      outputOk
    }
  }
}
