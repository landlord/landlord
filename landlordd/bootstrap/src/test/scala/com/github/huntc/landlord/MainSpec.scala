package com.github.huntc.landlord

import org.scalatest._

object MainSpec {
  var testMainArgs = Array.empty[String]
}

class MainSpec extends FlatSpec with Matchers {
  import MainSpec._

  "bootable main" should
    "invoke the test main" in {
      Main.main(Array("com.github.huntc.landlord.TestMain", "something"))
      testMainArgs shouldBe Array("something")
    }
}

object TestMain extends App {
  MainSpec.testMainArgs = args
}