package com.dont39.pz.serverreborn.networksyncoptimization.client

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NoticesTest {
  @Test
  fun `nothing to show`() {
    assertEquals(0, Notices().due(0))
  }

  @Test
  fun `the first repair shows at once`() {
    val n = Notices()
    n.repaired()
    assertEquals(1, n.due(123))
    assertEquals(0, n.due(124))
  }

  @Test
  fun `repairs inside the window add up to the next notice`() {
    val n = Notices()
    n.repaired()
    assertEquals(1, n.due(0))
    repeat(10) {
      n.repaired()
      assertEquals(0, n.due(it * 100L))
    }
    assertEquals(0, n.due(Notices.WINDOW_MS - 1))
    assertEquals(10, n.due(Notices.WINDOW_MS))
    assertEquals(0, n.due(Notices.WINDOW_MS + 1))
  }
}
