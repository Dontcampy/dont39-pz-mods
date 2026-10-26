package com.dont39.pz.serverreborn.networksyncoptimization.client

/**
 * The repair notice's rate limit (R9, D6): at most one per [WINDOW_MS]; repairs inside the window add to the next
 * one's count. No game types. Main thread only.
 */
internal class Notices {
  private var waiting = 0
  private var lastShown = 0L
  private var shown = false

  fun repaired() {
    waiting++
  }

  /** The repairs to show now, or 0 when there are none or the last notice is too recent. */
  fun due(now: Long): Int {
    if (waiting == 0 || (shown && now - lastShown < WINDOW_MS)) {
      return 0
    }
    val n = waiting
    waiting = 0
    lastShown = now
    shown = true
    return n
  }

  companion object {
    const val WINDOW_MS = 10_000L
  }
}
