package com.dont39.pz.modsync.protocol

import com.dont39.pz.modsync.protocol.Trailer.Decoded
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TrailerTest {
  // what connection-details holds before the trailer; its last part is length-prefixed
  private val vanilla = ByteArray(200) { (it * 7).toByte() }

  private fun packet(trailer: ByteArray, capacity: Int = vanilla.size + trailer.size): ByteBuffer {
    val b = ByteBuffer.allocate(capacity)
    b.put(vanilla).put(trailer)
    b.limit(b.position()).position(0)
    return b
  }

  private fun footer(payload: ByteArray, length: Int = payload.size, magic: Long = Trailer.MAGIC): ByteArray =
    ByteBuffer.allocate(payload.size + 12).put(payload).putInt(length).putLong(magic).array()

  private fun reason(b: ByteBuffer) = (Trailer.decode(b) as Decoded.None).reason

  @Test
  fun `the port is read back from the end, leaving the buffer as it was`() {
    val b = packet(Trailer.encode(16300))
    b.order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(Decoded.Port(16300), Trailer.decode(b))
    assertEquals(0, b.position())
    assertEquals(vanilla.size + 15, b.limit())
    assertEquals(ByteOrder.LITTLE_ENDIAN, b.order())
  }

  @Test
  fun `the limit, not the capacity, ends the packet`() {
    // the single-packet path reuses a large buffer and limits it to what arrived
    assertEquals(Decoded.Port(65535), Trailer.decode(packet(Trailer.encode(65535), capacity = 4096)))
  }

  @Test
  fun `a vanilla server sends no trailer`() {
    assertEquals("the server sent no mod sync location", reason(packet(ByteArray(0))))
    assertEquals("the server sent no mod sync location", reason(ByteBuffer.allocate(5)))
  }

  @Test
  fun `a length that does not fit is no session`() {
    assertTrue("do not fit" in reason(packet(footer(byteArrayOf(1, 0x3F, 0x00), length = 10_000))))
    assertTrue("do not fit" in reason(packet(footer(byteArrayOf(), length = 0))))
    assertTrue("do not fit" in reason(packet(footer(byteArrayOf(1, 0x3F, 0x00), length = -1))))
  }

  @Test
  fun `another version or layout is no session`() {
    assertTrue("version 2" in reason(packet(footer(byteArrayOf(2, 0x3F, 0x00)))))
    assertTrue("4 bytes" in reason(packet(footer(byteArrayOf(1, 0x3F, 0x00, 0)))))
    assertTrue("port 0" in reason(packet(footer(byteArrayOf(1, 0, 0)))))
  }

  @Test
  fun `the encoding is pinned`() {
    assertEquals(
      listOf(1, 0x3F, 0xAC, 0, 0, 0, 3, 0x44, 0x33, 0x39, 0x4D, 0x53, 0x59, 0x4E, 0x43),
      Trailer.encode(16300).map { it.toInt() and 0xFF },
    )
    assertThrows<IllegalArgumentException> { Trailer.encode(0) }
    assertThrows<IllegalArgumentException> { Trailer.encode(65536) }
  }
}
