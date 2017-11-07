package com.github.huntc.landlord

import java.io.ByteArrayOutputStream

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer
import akka.testkit._
import akka.util.ByteString
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.scalatest._

import scala.concurrent.Future

class ProcessParameterParserSpec extends TestKit(ActorSystem("ProcessParameterParserSpec"))
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

      val TarRecordSize = 10240

      Source
        .single(
          ByteString(cl + "\n") ++
            ByteString(tar) ++
            ByteString(stdinStr)
        )
        .via(new ProcessParameterParser)
        .runFoldAsync(0 -> succeed) {
          case ((ordinal, _), ProcessParameterParser.CommandLine(v)) =>
            Future.successful(1 -> assert(ordinal == 0 && v == cl))
          case ((ordinal, _), ProcessParameterParser.Archive(v)) =>
            val complete = v.runFold(0L)(_ + _.size)
            complete.map(tarSize => 2 -> assert(ordinal == 1 && tarSize == TarRecordSize))
          case ((ordinal, _), ProcessParameterParser.Stdin(v)) =>
            val complete = v.runFold("")(_ ++ _.utf8String)
            complete.map(input => 3 -> assert(ordinal == 2 && input == stdinStr))
        }
        .map {
          case (count, Succeeded) if count == 3 => Succeeded
          case (count, Succeeded)               => assert(count == 3)
          case (_, lastAssertion)               => lastAssertion
        }
    }
  }
}
