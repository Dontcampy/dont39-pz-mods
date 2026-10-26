package com.dont39.pz.serverreborn.servertickrate

/** The game's server constants that count 10 Hz ticks, at another tick rate. */
internal object TickScale {
  const val VANILLA_RATE = 10
  const val MAX_RATE = 20

  /** `Rand.AdjustForFramerate` at [rate]: the game's client formula with [rate] as its frame rate, as 10 is on the server. */
  fun adjustForFramerate(n: Int, rate: Int): Int = (n * (rate / 30f)).toInt()

  /** [ticks] at 10 Hz as ticks at [rate], to the nearest tick. */
  fun ticks(ticks: Int, rate: Int): Int = (ticks * rate + VANILLA_RATE / 2) / VANILLA_RATE

  /** The tick limiter's period for [rate], to the nearest millisecond. */
  fun periodMillis(rate: Int): Long = ((1000 + rate / 2) / rate).toLong()
}
