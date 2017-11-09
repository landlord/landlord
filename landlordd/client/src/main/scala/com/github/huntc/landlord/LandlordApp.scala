package com.github.huntc.landlord

import java.util.concurrent.{ ExecutorService, Executors }

object SharedExecutor {
  // TODO inject num threads via config
  val executor: ExecutorService = Executors.newFixedThreadPool(10)
}

trait LandlordClient {
  def trap(signal: Int): Unit = {}

  def getSharedExecutor(): ExecutorService = SharedExecutor.executor
}

class LandlordApp extends LandlordClient
