package com.dont39.pz.serverreborn.network.stats

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimingsTest {
  @Test
  fun `empty`() {
    val t = Timings()
    assertEquals(0, t.percentileNanos(0.99))
    assertEquals(0, t.maxNanos)
    assertEquals(0, t.meanNanos)
  }

  @Test
  fun `a percentile is its bucket's bound, at most twice the value`() {
    val t = Timings()
    repeat(99) { t.record(100_000) }
    t.record(5_000_000)
    val p99 = t.percentileNanos(0.99)
    assertTrue(p99 in 100_000..200_000, "p99 $p99")
    assertEquals(5_000_000, t.percentileNanos(1.0))
    assertEquals(5_000_000, t.maxNanos)
    assertEquals(100, t.count)
  }

  @Test
  fun `never above the max`() {
    val t = Timings()
    t.record(1_500_000)
    assertEquals(1_500_000, t.percentileNanos(0.5))
  }

  @Test
  fun `reset`() {
    val t = Timings()
    t.record(10)
    t.reset()
    assertEquals(0, t.count)
    assertEquals(0, t.maxNanos)
  }
}
