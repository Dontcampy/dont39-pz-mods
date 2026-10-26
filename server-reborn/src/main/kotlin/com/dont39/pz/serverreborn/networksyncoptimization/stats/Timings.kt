package com.dont39.pz.serverreborn.networksyncoptimization.stats

import kotlin.math.ceil

/**
 * Durations in power-of-two microsecond buckets: a percentile is the upper bound of its bucket, at most twice the true
 * value, which is enough to see whether a phase stays under a millisecond. Not thread-safe.
 */
internal class Timings {
  // bucket b holds [2^(b-1), 2^b) microseconds; bucket 0 under one
  private val counts = LongArray(BUCKETS)
  private var totalNanos = 0L

  var count = 0L
    private set
  var maxNanos = 0L
    private set

  val meanNanos: Long get() = if (count == 0L) 0 else totalNanos / count

  fun record(nanos: Long) {
    val micros = nanos.coerceAtLeast(0) / 1000
    val bucket = if (micros == 0L) 0 else minOf(BUCKETS - 1, Long.SIZE_BITS - micros.countLeadingZeroBits())
    counts[bucket]++
    count++
    totalNanos += nanos
    maxNanos = maxOf(maxNanos, nanos)
  }

  /** The bucket bound at or under which [fraction] of the durations fall; 0 without any. */
  fun percentileNanos(fraction: Double): Long {
    if (count == 0L) {
      return 0
    }
    val wanted = ceil(count * fraction).toLong()
    var seen = 0L
    counts.forEachIndexed { bucket, n ->
      seen += n
      if (seen >= wanted) {
        return minOf(maxNanos, (1L shl bucket) * 1000)
      }
    }
    return maxNanos
  }

  fun reset() {
    counts.fill(0)
    count = 0
    maxNanos = 0
    totalNanos = 0
  }

  private companion object {
    const val BUCKETS = 32
  }
}
