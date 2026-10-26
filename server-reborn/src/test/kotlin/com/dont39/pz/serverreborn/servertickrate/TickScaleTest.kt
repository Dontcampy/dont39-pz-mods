package com.dont39.pz.serverreborn.servertickrate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TickScaleTest {
  // the game's server branch of RandInterface.AdjustForFramerate
  private fun vanillaServer(n: Int): Int = (n * 0.33333334f).toInt()

  @Test
  fun `at 10 the rolls are the game's`() {
    for (n in 0..200_000) {
      assertEquals(vanillaServer(n), TickScale.adjustForFramerate(n, 10), "n = $n")
    }
  }

  @Test
  fun `at 20 the argument is doubled before the third`() {
    for (n in 0..100_000) {
      assertEquals(vanillaServer(2 * n), TickScale.adjustForFramerate(n, 20), "n = $n")
    }
    assertEquals(20, TickScale.adjustForFramerate(30, 20))
  }

  @Test
  fun `between 10 and 20 a roll's chance per second stays the game's`() {
    // chance per tick 1/adjusted, ticks per second rate: equal within the integer truncation
    for (rate in 10..20) {
      for (n in listOf(400, 1000, 9000, 80000)) {
        val perSecond = rate.toDouble() / TickScale.adjustForFramerate(n, rate)
        val vanilla = 10.0 / vanillaServer(n)
        assertEquals(vanilla, perSecond, vanilla * 0.01, "rate $rate, n = $n")
      }
    }
  }

  @Test
  fun `a world sound lives as long in real time`() {
    assertEquals(16, TickScale.ticks(16, 10))
    assertEquals(24, TickScale.ticks(16, 15))
    assertEquals(32, TickScale.ticks(16, 20))
    assertEquals(21, TickScale.ticks(16, 13))
  }

  @Test
  fun `the period is the nearest millisecond`() {
    assertEquals(100, TickScale.periodMillis(10))
    assertEquals(67, TickScale.periodMillis(15))
    assertEquals(50, TickScale.periodMillis(20))
    for (rate in TickScale.VANILLA_RATE..TickScale.MAX_RATE) {
      assertEquals(rate.toDouble(), 1000.0 / TickScale.periodMillis(rate), rate * 0.01, "rate $rate")
    }
  }
}
