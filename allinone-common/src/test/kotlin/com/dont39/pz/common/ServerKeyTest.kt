package com.dont39.pz.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ServerKeyTest {
  @Test
  fun `the host is kept as typed, only trimmed`() {
    assertEquals("example.com:16261", serverKey(" example.com ", 16261))
    assertEquals("EXAMPLE.com:16261", serverKey("EXAMPLE.com", 16261))
    assertEquals("2001:db8::1:16261", serverKey("2001:db8::1", 16261))
  }
}
