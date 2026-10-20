package com.dont39.pz.modsync

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One join's download counts, written by its worker and read by the game thread, and its cancel flag. */
internal class Progress {
  @Volatile
  var totalFiles = 0
    private set

  @Volatile
  var totalBytes = 0L
    private set

  private val files = AtomicInteger()
  private val bytes = AtomicLong()

  val filesDone: Int
    get() = files.get()

  val bytesDone: Long
    get() = bytes.get()

  @Volatile
  var cancelled = false
    private set

  fun begin(files: Int, bytes: Long) {
    totalFiles = files
    totalBytes = bytes
  }

  fun addBytes(n: Int) {
    bytes.addAndGet(n.toLong())
  }

  fun fileDone() {
    files.incrementAndGet()
  }

  fun cancel() {
    cancelled = true
  }

  fun checkCancelled() {
    if (cancelled) {
      throw CancellationException("cancelled")
    }
  }
}

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

  /** For tests: waits up to [millis] for the outcome. */
  fun await(millis: Long): Outcome<T>? {
    thread.join(millis)
    return outcome
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
