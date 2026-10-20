package com.dont39.pz.modsync.client.download

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
