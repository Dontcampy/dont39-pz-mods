package com.dont39.pz.modsync.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkerTest {
  private companion object {
    const val WAIT_MILLIS = 10_000L
  }

  @Test
  fun `a worker reports what its body did`() {
    assertEquals(Worker.Outcome.Done(3), Worker("test") { 3 }.start().await(WAIT_MILLIS))
    val error = IllegalStateException("boom")
    assertEquals(Worker.Outcome.Failed(error), Worker<Int>("test") { throw error }.start().await(WAIT_MILLIS))
    val sleeping = Worker("test") { Thread.sleep(WAIT_MILLIS) }.start()
    sleeping.cancel()
    assertEquals(Worker.Outcome.Cancelled, sleeping.await(WAIT_MILLIS))
  }
}
