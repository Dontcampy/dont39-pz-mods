package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.client.download.Progress

/** Runs [body] on its own daemon thread; the game thread polls [outcome] and may [cancel]. */
internal class Worker<T>(name: String, private val body: (Progress) -> T) {
  sealed interface Outcome<out T> {
    data class Done<T>(val value: T) : Outcome<T>

    data class Failed(val error: Throwable) : Outcome<Nothing>

    data object Cancelled : Outcome<Nothing>
  }

  val progress = Progress()

  /** Null while running. */
  @Volatile
  var outcome: Outcome<T>? = null
    private set

  private val thread = Thread(::run, name).apply { isDaemon = true }

  fun start(): Worker<T> {
    thread.start()
    return this
  }

  /** The interrupt ends a blocking HTTP call or file write. */
  fun cancel() {
    progress.cancel()
    thread.interrupt()
  }

  private fun run() {
    outcome = try {
      Outcome.Done(body(progress))
    } catch (t: Throwable) {
      // whatever a cancelled body throws, e.g. an IOException from the interrupted read
      if (progress.cancelled) Outcome.Cancelled else Outcome.Failed(t)
    }
  }
}
