package com.github.huntc.landlord

import akka.util.{ ByteString, ByteStringBuilder }
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ThrottleMode }
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

  implicit val ma: ActorMaterializer = ActorMaterializer()

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

      val stdinStr = "some stdin\nsome more stdin\n"

      val signal = 15

      val TarRecordSize = 10240

      Source
        .single(
          ByteString(cl + "\n") ++
            ByteString(tar) ++
            ByteString(stdinStr + "\u0004") ++
            ByteString.newBuilder.putInt(signal)(ByteOrder.BIG_ENDIAN).result()
        )
        .via(new JvmExecutor.ProcessParameterParser)
        .runFoldAsync(0 -> succeed) {
          case ((ordinal, _), JvmExecutor.CommandLine(v)) =>
            Future.successful(1 -> assert(ordinal == 0 && v == cl))
          case ((ordinal, _), JvmExecutor.Archive(v)) =>
            val complete = v.runFold(0L)(_ + _.size)
            complete.map(tarSize => 2 -> assert(ordinal == 1 && tarSize == TarRecordSize))
          case ((ordinal, _), JvmExecutor.Stdin(v)) =>
            val complete = v.runFold("")(_ ++ _.utf8String)
            complete.map(input => 3 -> assert(ordinal == 2 && input == stdinStr))
          case ((ordinal, _), JvmExecutor.Signal(v)) =>
            Future.successful(4 -> assert(ordinal == 3 && v == signal))
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
      val stdin = new ThreadGroupInputStream(System.in)
      val stdout = new ThreadGroupPrintStream(System.out)
      val stderr = new ThreadGroupPrintStream(System.err)
      System.setIn(stdin)
      System.setOut(stdout)
      System.setErr(stderr)

      val properties = new ThreadGroupProperties(System.getProperties)
      System.setProperties(properties)

      val securityManager = new ThreadGroupSecurityManager(System.getSecurityManager)
      System.setSecurityManager(securityManager)

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

      val stdinStr = "Hello World\n"

      val in =
        Source(
          List(
            ByteString(cl + "\n") ++
              ByteString(tar) ++
              ByteString(stdinStr + "\u0004"),
            ByteString("\u0015")
          )
        ).throttle(1, 15.seconds.dilated, 1, ThrottleMode.Shaping) // We don't really want this test to receive the signal element, so we delay it.

      val out = Promise[Source[ByteString, akka.NotUsed]]()
      val processDirPath = Files.createTempDirectory("jvm-executor-spec")
      processDirPath.toFile.deleteOnExit()

      val process =
        system.actorOf(JvmExecutor.props(
          "some-process",
          properties, securityManager, useDefaultSecurityManager = false, preventShutdownHooks = true,
          stdin, 3.seconds.dilated, stdout, stderr,
          in, out,
          100.milliseconds.dilated,
          processDirPath
        ))

      val outputOk =
        out.future
          .flatMap { outSource =>
            outSource
              .runFold(ByteString.empty)(_ ++ _)
              .map { bytes =>
                val (_, _, _, byteStrings) =
                  bytes.foldLeft((false, 0, 0, List.empty[ByteStringBuilder])) {
                    case ((false, 0, 0, bs), b) if b == 'o'.toByte || b == 'e'.toByte =>
                      (true, 0, 0, bs)
                    case ((false, 0, 0, bs), b) if b == 'x'.toByte =>
                      (false, 0, 4, bs :+ ByteString.newBuilder)
                    case ((true, 0, 0, bs), b) =>
                      (true, 1, (b & 0xff) << 24, bs)
                    case ((true, 1, s, bs), b) =>
                      (true, 2, s | ((b & 0xff) << 16), bs)
                    case ((true, 2, s, bs), b) =>
                      (true, 3, s | ((b & 0xff) << 8), bs)
                    case ((true, 3, s, bs), b) =>
                      (false, 0, s | (b & 0xff), bs :+ ByteString.newBuilder)
                    case ((false, 0, s, bs), b) if s > 0 =>
                      bs.last.putByte(b)
                      (false, 0, s - 1, bs)
                  }
                val outputBytes = byteStrings.dropRight(1).foldLeft(ByteString.empty)(_ ++ _.result)
                val exitCodeBytes = byteStrings.last.result
                assert(outputBytes.utf8String == stdinStr && exitCodeBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == 0)
              }
          }(ExecutionContext.Implicits.global) // We use this context to get us off the ScalaTest one (which would hang this)

      val watcher = TestProbe()
      watcher.watch(process)
      watcher.expectTerminated(process, max = 5.seconds.dilated)

      outputOk
    }
  }
}
