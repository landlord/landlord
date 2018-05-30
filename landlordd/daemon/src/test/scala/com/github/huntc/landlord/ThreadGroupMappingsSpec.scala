package com.github.huntc.landlord

import akka.util.ByteString
import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, PrintStream }
import java.util.Properties
import org.scalatest._
import scala.collection.JavaConverters._

class ThreadGroupMappingsSpec extends WordSpec with Matchers {

  "A thread group mapping" should {
    "return the fallback when not inited" in {
      val threadGroupMapping =
        new ThreadGroupMapping[Int] {
          protected val _fallback = 1
        }

      threadGroupMapping.get shouldBe 1
    }

    "return the inited value when inited" in {
      val threadGroupMapping =
        new ThreadGroupMapping[Int] {
          protected val _fallback = 1
        }

      threadGroupMapping.init(2)
      threadGroupMapping.get shouldBe 2
    }

    "return the fallback value when inited but then destroyed" in {
      val threadGroupMapping =
        new ThreadGroupMapping[Int] {
          protected val _fallback = 1
        }

      threadGroupMapping.init(2)
      threadGroupMapping.destroy()
      threadGroupMapping.get shouldBe 1
    }
  }

  "A thread group input stream" should {
    "read from the fallback in case of no others being declared" in {
      val fallbackBytes = ByteString("hi").toArray
      val fallbackBais = new ByteArrayInputStream(fallbackBytes)
      val threadGroupIs = new ThreadGroupInputStream(fallbackBais)
      threadGroupIs.read shouldBe fallbackBytes(0)
      threadGroupIs.read shouldBe fallbackBytes(1)
      fallbackBais.read shouldBe -1
    }

    "read from the thread group stream given the init" in {
      val fallbackBytes = ByteString("hi").toArray
      val fallbackBais = new ByteArrayInputStream(fallbackBytes)
      val threadGroupIs = new ThreadGroupInputStream(fallbackBais)
      val threadGroupBytes = ByteString("ok").toArray
      val threadGroupBais = new ByteArrayInputStream(threadGroupBytes)
      threadGroupIs.init(threadGroupBais)
      threadGroupIs.read shouldBe threadGroupBytes(0)
      threadGroupIs.read shouldBe threadGroupBytes(1)
      fallbackBais.read shouldBe fallbackBytes(0)
    }
  }

  "A thread group print stream" should {
    "write to the fallback in case of no others being declared when using the write byte method" in {
      val fallbackBaos = new ByteArrayOutputStream()
      val threadGroupOs = new ThreadGroupPrintStream(new PrintStream(fallbackBaos))
      val threadGroupBytes = ByteString("hi").toArray
      threadGroupOs.write(threadGroupBytes(0).toInt)
      threadGroupOs.write(threadGroupBytes(1).toInt)
      fallbackBaos.toByteArray shouldBe threadGroupBytes
    }

    "write to the fallback in case of no others being declared when using the write array method" in {
      val fallbackBaos = new ByteArrayOutputStream()
      val threadGroupOs = new ThreadGroupPrintStream(new PrintStream(fallbackBaos))
      val threadGroupBytes = ByteString("hi").toArray
      threadGroupOs.write(threadGroupBytes)
      fallbackBaos.toByteArray shouldBe threadGroupBytes
    }

    "write to the thread group stream given the init using the write array method" in {
      val fallbackBaos = new ByteArrayOutputStream()
      val threadGroupOs = new ThreadGroupPrintStream(new PrintStream(fallbackBaos))
      val threadGroupBaos = new ByteArrayOutputStream()
      threadGroupOs.init(new PrintStream(threadGroupBaos))
      val threadGroupBytes = ByteString("hi").toArray
      fallbackBaos.toByteArray.length shouldBe 0
      threadGroupOs.write(threadGroupBytes)
      threadGroupBaos.toByteArray shouldBe threadGroupBytes
    }
  }

  "A thread group's properties" should {
    "return the standard user.dir" in {
      val threadGroupProperties = new ThreadGroupProperties(System.getProperties)
      assert(threadGroupProperties.getProperty("user.dir").length > 0)
    }

    "return an overridden user.dir when accessed via an inited thread" in {
      val threadGroupProperties = new ThreadGroupProperties(System.getProperties)
      val newProperties = new Properties(System.getProperties)
      newProperties.setProperty("user.dir", "/xyz")
      threadGroupProperties.init(newProperties)
      threadGroupProperties.getProperty("user.dir", "some-default") shouldBe "/xyz"

      val entries =
        threadGroupProperties
          .entrySet()
          .asScala
          .toVector
          .map(e => e.getKey.toString -> e.getValue.toString)
          .toMap

      assert(entries.get("user.dir").contains("/xyz"))
    }
  }

  "A thread group's security manager" should {
    "permit exit" in {
      val threadGroupSecurityManager =
        new ThreadGroupSecurityManager(
          new SecurityManager() {
            override def checkExit(status: Int): Unit =
              ()
          }
        )
      threadGroupSecurityManager.checkExit(0)
    }

    "not permit exit when accessed via an inited thread" in {
      val threadGroupSecurityManager =
        new ThreadGroupSecurityManager(
          new SecurityManager() {
            override def checkExit(status: Int): Unit =
              ()
          }
        )
      threadGroupSecurityManager.init(new SecurityManager {
        override def checkExit(status: Int): Unit =
          throw new SecurityException()
      })
      a[SecurityException] shouldBe thrownBy {
        threadGroupSecurityManager.checkExit(0)
      }
    }
  }
}
