package com.dont39.pz.modsync.client

/** Waits up to [millis] for the outcome, as the game thread would poll it. */
internal fun <T> Worker<T>.await(millis: Long): Worker.Outcome<T>? {
  val until = System.currentTimeMillis() + millis
  while (outcome == null && System.currentTimeMillis() < until) {
    Thread.sleep(5)
  }
  return outcome
}
