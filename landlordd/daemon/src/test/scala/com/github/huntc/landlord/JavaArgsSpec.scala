package com.github.huntc.landlord

import org.scalatest._
import scala.collection.immutable.Seq

class JavaArgsSpec extends WordSpec with Matchers {
  "parse" should {
    "Accept just two regular classpath arg and the main class" in {
      val parsed = JavaArgs.parse(Seq("-cp", "somepath:someotherpath", "mainclass"))
      assert(parsed.contains(JavaArgs(Seq("somepath", "someotherpath"), Seq.empty, ClassExecutionMode("mainclass", Seq.empty), Seq.empty)))
    }

    "Accept just a glob classpath arg and the main class" in {
      val parsed = JavaArgs.parse(List("-cp", "lib/*", "mainclass"))
      assert(parsed.contains(JavaArgs(Seq("lib/*"), Seq.empty, ClassExecutionMode("mainclass", Seq.empty), Seq.empty)))
    }

    "Return just the main class when no args" in {
      val parsed = JavaArgs.parse(List("mainclass"))
      assert(parsed.contains(JavaArgs(Seq.empty, Seq.empty, ClassExecutionMode("mainclass", Seq.empty), Seq.empty)))
    }

    "Return the main class and args" in {
      val parsed = JavaArgs.parse(List("mainclass", "mainarg0", "mainarg1"))
      assert(parsed.contains(JavaArgs(Seq.empty, Seq.empty, ClassExecutionMode("mainclass", Seq("mainarg0", "mainarg1")), Seq.empty)))
    }

    "Parse properties" in {
      val parsed = JavaArgs.parse(List("-Dtest1=one", "-Dtest2=two", "mainclass"))
      assert(parsed.contains(JavaArgs(Seq.empty, Seq.empty, ClassExecutionMode("mainclass", Seq.empty), Seq("test1" -> "one", "test2" -> "two"))))
    }

    "Fail when given invalid flags" in {
      val parsed = JavaArgs.parse(List("-what", "mainclass"))
      assert(parsed.left.exists(_ == Seq("Unrecognized option: -what")))
    }
  }
}