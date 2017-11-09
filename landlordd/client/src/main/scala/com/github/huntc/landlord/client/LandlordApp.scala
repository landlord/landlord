package com.github.huntc.landlord.client

import java.util.concurrent.{ ExecutorService, Executors }

object SharedExecutor {
  // TODO inject num threads via config
  val executor: ExecutorService = Executors.newFixedThreadPool(10)
}

// Scala clients will mixin this trait
trait LandlordClient {
  def trap(signal: Int): Unit = {}

  def getSharedExecutor(): ExecutorService = SharedExecutor.executor
}

// Java clients will extend this class
class LandlordApp extends LandlordClient
