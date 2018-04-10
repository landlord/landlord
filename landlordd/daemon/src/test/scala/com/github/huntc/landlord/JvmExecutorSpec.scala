package com.github.huntc.landlord

import akka.util.{ ByteString, ByteStringBuilder }
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ThrottleMode }
import akka.stream.scaladsl.Source
import akka.testkit._
import java.io.ByteArrayOutputStream
import java.net.URLClassLoader
import java.nio.ByteOrder
import java.nio.file.{ Files, Paths }

import org.apache.commons.compress.archivers.tar.{ TarArchiveEntry, TarArchiveOutputStream }
import org.scalatest._

import scala.concurrent.{ ExecutionContext, Promise }
import scala.concurrent.duration._

class JvmExecutorSpec extends TestKit(ActorSystem("JvmExecutorSpec"))
  with AsyncWordSpecLike with Matchers with BeforeAndAfterAll {

  override def afterAll: Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val ma: ActorMaterializer = ActorMaterializer()

  "The classpath resolver" should {
    "resolve a non-glob" in {
      val resolved = JvmExecutor.resolvePaths(Paths.get("/tmp"), Paths.get("a.class")).toList
      assert(resolved === List(Paths.get("/tmp/a.class")))
    }

    "resolve a glob" in {
      val base = Files.createTempDirectory("classpath-resolver-spec")
      base.toFile.deleteOnExit()
      base.resolve("lib").toFile.mkdir()
      Files.createFile(base.resolve("lib/a.class"))
      Files.createFile(base.resolve("lib/b.jar"))
      Files.createFile(base.resolve("lib/c.txt"))
      val resolved = JvmExecutor.resolvePaths(base, Paths.get("lib/*")).toSet
      assert(resolved === Set(base.resolve("lib/a.class"), base.resolve("lib/b.jar")))
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

      val processId = 123

      val cl = "-Dgreeting=This is a test\u0000-cp\u0000classes\u0000example.Hello\u0000Hello World #1\u0000Hi #2"

      val TarBlockSize = 10240
      val tar = {
        val bos = new ByteArrayOutputStream()
        val tos = new TarArchiveOutputStream(bos, TarBlockSize)
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
              ByteString(stdinStr),
            ByteString.empty
          )
        ).throttle(1, 15.seconds.dilated, 1, ThrottleMode.Shaping) // We don't want this test to give up on stdin, so we delay it.

      val out = Promise[Source[ByteString, akka.NotUsed]]()
      val processDirPath = Files.createTempDirectory("jvm-executor-spec")

      try {
        val process =
          system.actorOf(JvmExecutor.props(
            123,
            properties, securityManager, useDefaultSecurityManager = false, preventShutdownHooks = true,
            stdin, 3.seconds.dilated, stdout, stderr,
            in, out,
            12.seconds.dilated, 100.milliseconds.dilated,
            processDirPath,
            Map.empty
          ))

        val outputOk =
          out.future
            .flatMap { outSource =>
              outSource
                .runFold(ByteString.empty)(_ ++ _)
                .map { bytes =>
                  val processIdBytes = bytes.take(4)
                  val (_, _, _, byteStrings) =
                    bytes.drop(4).foldLeft((false, 0, 0, List.empty[ByteStringBuilder])) {
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
                  assert(
                    processIdBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == processId &&
                      outputBytes.utf8String == s"Argument #1: Hello World #1\nArgument #2: Hi #2\nThis is a test\n${stdinStr}" &&
                      exitCodeBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == 0)
                }
            }(ExecutionContext.Implicits.global) // We use this context to get us off the ScalaTest one (which would hang this)

        val watcher = TestProbe()
        watcher.watch(process)
        watcher.expectTerminated(process, max = 5.seconds.dilated)

        outputOk
      } finally {
        deleteRecursively(processDirPath)
      }
    }

    "work with profiles" in {
      // for this test, we're specifying an empty main classpath directory, and then selecting
      // a profile that points at our actual classes

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

      val processDirPath = Files.createTempDirectory("jvm-executor-spec")

      try {
        Files.createDirectories(processDirPath.resolve("classes").resolve("dep"))
        Files.createDirectories(processDirPath.resolve("classes").resolve("example"))

        {
          val classFile = Paths.get(getClass.getResource("/dep/Greeting.class").toURI)
          val data = Files.readAllBytes(classFile)
          Files.write(processDirPath.resolve("classes").resolve("dep").resolve("Greeting.class"), data)
        }

        {
          val classFile = Paths.get(getClass.getResource("/example/Profile.class").toURI)
          val data = Files.readAllBytes(classFile)
          Files.write(processDirPath.resolve("classes").resolve("example").resolve("Profile.class"), data)
        }

        val processId = 124

        val cl = "-Dlandlord.class-profile=myprofile\u0000-cp\u0000empty-dir\u0000example.Profile"

        val TarBlockSize = 10240
        val tar = {
          val bos = new ByteArrayOutputStream()
          val tos = new TarArchiveOutputStream(bos, TarBlockSize)
          try {
            {
              val te = new TarArchiveEntry("empty-dir/")
              tos.putArchiveEntry(te)
              tos.closeArchiveEntry()
            }
            tos.flush()
            tos.finish()
          } finally {
            tos.close()
          }
          bos.toByteArray
        }

        val in = Source(List(ByteString(cl + "\n") ++ ByteString(tar), ByteString.empty))
        val out = Promise[Source[ByteString, akka.NotUsed]]()

        val process =
          system.actorOf(JvmExecutor.props(
            124,
            properties, securityManager, useDefaultSecurityManager = false, preventShutdownHooks = true,
            stdin, 3.seconds.dilated, stdout, stderr,
            in, out,
            12.seconds.dilated, 100.milliseconds.dilated,
            processDirPath,
            Map("myprofile" -> new URLClassLoader(classPathUrls(processDirPath, classPathStrings("classes")).toArray))
          ))

        val outputOk =
          out.future
            .flatMap { outSource =>
              outSource
                .runFold(ByteString.empty)(_ ++ _)
                .map { bytes =>
                  val processIdBytes = bytes.take(4)
                  val (_, _, _, byteStrings) =
                    bytes.drop(4).foldLeft((false, 0, 0, List.empty[ByteStringBuilder])) {
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
                  assert(
                    processIdBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == processId &&
                      outputBytes.utf8String == "built-in\n" &&
                      exitCodeBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == 0)
                }
            }(ExecutionContext.Implicits.global) // We use this context to get us off the ScalaTest one (which would hang this)

        val watcher = TestProbe()
        watcher.watch(process)
        watcher.expectTerminated(process, max = 5.seconds.dilated)

        outputOk
      } finally {
        deleteRecursively(processDirPath)
      }
    }
  }

  private def deleteRecursively(path: java.nio.file.Path): Unit = {
    Files
      .walk(path, java.nio.file.FileVisitOption.FOLLOW_LINKS)
      .sorted(java.util.Comparator.reverseOrder())
      .forEach(f => assert(f.toFile.delete()))
  }
}
