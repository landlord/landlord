package com.github.huntc.landlord

import akka.util.{ ByteString, ByteStringBuilder }
import akka.actor.ActorSystem
import akka.stream.{ ActorMaterializer, ThrottleMode }
import akka.stream.scaladsl.Source
import akka.testkit._
import java.io.ByteArrayOutputStream
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
      processDirPath.toFile.deleteOnExit()

      val process =
        system.actorOf(JvmExecutor.props(
          123,
          properties, securityManager, useDefaultSecurityManager = false,
          stdin, 3.seconds.dilated, stdout, stderr,
          in, out,
          12.seconds.dilated, 100.milliseconds.dilated,
          1.second,
          processDirPath,
          List.empty
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
                    outputBytes.utf8String == s"Argument #1: Hello World #1\nArgument #2: Hi #2\nThis is a test\n${stdinStr}Good Bye!\n" &&
                    exitCodeBytes.iterator.getInt(ByteOrder.BIG_ENDIAN) == 0)
              }
          }(ExecutionContext.Implicits.global) // We use this context to get us off the ScalaTest one (which would hang this)

      val watcher = TestProbe()
      watcher.watch(process)
      watcher.expectTerminated(process, max = 10.seconds.dilated)

      outputOk
    }
  }

  "activeThreads" should {
    "work" in {
      val group1 = new ThreadGroup("test")
      val group2 = new ThreadGroup(group1, "test-child")

      try {
        assert(JvmExecutor.activeThreads(group1).isEmpty)

        assert(JvmExecutor.activeThreads(group2).isEmpty)

        @volatile
        var done = false
        val started1 = Promise[Unit]
        val started2 = Promise[Unit]

        val thread1 =
          new Thread(group1, { () =>
            started1.success(())
            while (!done) {
              Thread.sleep(10)
            }
          })

        val thread2 =
          new Thread(group2, { () =>
            started2.success(())
            while (!done) {
              Thread.sleep(10)
            }
          })

        thread1.start()
        thread2.start()

        assert(JvmExecutor.activeThreads(group1) == Set(thread1, thread2))
        assert(JvmExecutor.activeThreads(group2) == Set(thread2))

        done = true
        thread1.join()
        thread2.join()

        assert(JvmExecutor.activeThreads(group1) == Set.empty)
        assert(JvmExecutor.activeThreads(group2) == Set.empty)
      } finally {
        group2.destroy()
        group1.destroy()
      }
    }
  }

  "memberOfThreadGroup" should {
    "work" in {
      val parentA = new ThreadGroup("parentA")
      val parentB = new ThreadGroup("parentB")
      val childA = new ThreadGroup(parentA, "childA")
      val childB = new ThreadGroup(parentB, "childB")

      try {
        assert(!JvmExecutor.memberOfThreadGroup(null, null))

        assert(JvmExecutor.memberOfThreadGroup(parentA, parentA))
        assert(JvmExecutor.memberOfThreadGroup(parentB, parentB))

        assert(!JvmExecutor.memberOfThreadGroup(parentA, parentB))
        assert(!JvmExecutor.memberOfThreadGroup(parentB, parentA))

        assert(JvmExecutor.memberOfThreadGroup(childA, parentA))
        assert(!JvmExecutor.memberOfThreadGroup(parentA, childA))
        assert(!JvmExecutor.memberOfThreadGroup(childB, parentA))

        assert(JvmExecutor.memberOfThreadGroup(childB, parentB))
        assert(!JvmExecutor.memberOfThreadGroup(parentB, childB))
        assert(!JvmExecutor.memberOfThreadGroup(childA, parentB))
      } finally {
        childA.destroy()
        childB.destroy()
        parentA.destroy()
        parentB.destroy()
      }
    }
  }

  "removeShutdownHooks" should {
    "work" in {
      val value = new java.util.concurrent.atomic.AtomicInteger(0)
      val group = new ThreadGroup("test")
      val group2 = new ThreadGroup("test2")
      @volatile
      var hook1: Thread = null
      @volatile
      var hook2: Thread = null
      @volatile
      var hook3: Thread = null

      try {
        val t1 = new Thread(group, { () =>
          hook1 = sys.addShutdownHook {
            value.addAndGet(1)
          }
        })

        val t2 = new Thread(group, { () =>
          hook2 = sys.addShutdownHook {
            value.addAndGet(2)
          }
        })

        val t3 = new Thread(group2, { () =>
          hook3 = sys.addShutdownHook {
            value.addAndGet(3)
          }
        })

        t1.start()
        t2.start()
        t3.start()
        t1.join()
        t2.join()
        t3.join()

        val hooks1 = JvmExecutor.removeShutdownHooks(group)
        val hooks2 = JvmExecutor.removeShutdownHooks(group)

        assert(hooks1 == Set(hook1, hook2))
        assert(hooks2 == Set.empty)
        assert(value.get == 0)

        hooks1.foreach(_.start())
        hooks1.foreach(_.join())

        assert(value.get == 3)

        val hooks3 = JvmExecutor.removeShutdownHooks(group2)
        val hooks4 = JvmExecutor.removeShutdownHooks(group2)

        assert(hooks3 == Set(hook3))
        assert(hooks4 == Set.empty)

        hooks3.foreach(_.start())
        hooks3.foreach(_.join())

        assert(value.get == 6)
      } finally {
        group.destroy()
        group2.destroy()
      }
    }
  }
}
