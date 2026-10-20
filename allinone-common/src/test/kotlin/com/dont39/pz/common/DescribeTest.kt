package com.dont39.pz.common

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DescribeTest {
  @Test
  fun `a failure reads as its type and message`() {
    assertEquals("ConnectException: Connection refused", describe(ConnectException("Connection refused")))
    assertEquals("IOException", describe(IOException()))
  }

  @Test
  fun `an end of stream is the other end closing`() {
    assertEquals("closed by the other end", describe(EOFException("unexpected")))
  }
}
