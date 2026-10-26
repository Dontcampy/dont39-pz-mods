package com.dont39.pz.serverreborn.network.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.ByteBuffer

class TrailerTest {
  private val details = ByteArray(17) { (it + 1).toByte() }

  // mod sync's record: [byte 1][short port][int 3][long magic]
  private val modSync =
    ByteBuffer.allocate(15).put(1).putShort(16262).putInt(3).putLong(Trailer.MOD_SYNC_MAGIC).array()

  private fun written(vararg parts: ByteArray): ByteBuffer =
    ByteBuffer.allocate(1024).also { b -> parts.forEach(b::put) }

  /** As the client's copy: limit at the end of the data. */
  private fun received(written: ByteBuffer): ByteBuffer =
    ByteBuffer.wrap(written.array(), 0, written.position()).slice()

  private fun offered(data: ByteBuffer) = assertInstanceOf<Trailer.Decoded.Offered>(Trailer.decode(data)).protocol

  private fun none(data: ByteBuffer) = assertInstanceOf<Trailer.Decoded.None>(Trailer.decode(data)).reason

  @Test
  fun `round trip`() {
    val out = written(details)
    Trailer.append(out, 7)
    val input = received(out)
    assertEquals(7, offered(input))
    assertEquals(0, input.position())
    assertEquals(details.size + 14, input.limit())
    assertArrayEquals(details, ByteArray(details.size).also { input.get(0, it) })
  }

  @Test
  fun `a vanilla server`() {
    assertTrue(none(received(written(details))).contains("does not run Server Reborn"))
  }

  @Test
  fun `short data`() {
    none(ByteBuffer.allocate(3))
  }

  @Test
  fun `goes before mod sync's record, so mod sync still finds its own at the end`() {
    val out = written(details, modSync)
    Trailer.append(out, 1)
    val input = received(out)
    assertArrayEquals(modSync, ByteArray(modSync.size).also { input.get(input.limit() - modSync.size, it) })
    assertEquals(1, offered(input))
  }

  @Test
  fun `steps over mod sync's record appended after it`() {
    val out = written(details)
    Trailer.append(out, 1)
    out.put(modSync)
    assertEquals(1, offered(received(out)))
  }

  @Test
  fun `another trailer version`() {
    val out = written(details)
    Trailer.append(out, 1)
    out.put(details.size, (Trailer.TRAILER_VERSION + 1).toByte())
    assertTrue(none(received(out)).contains("version"))
  }

  @Test
  fun `a length that does not fit`() {
    none(ByteBuffer.allocate(14).put(1).put(1).putInt(1000).putLong(Trailer.MAGIC).flip())
  }

  @Test
  fun `decode leaves the buffer`() {
    val out = written(details)
    Trailer.append(out, 1)
    val input = received(out)
    input.position(5)
    Trailer.decode(input)
    assertEquals(5, input.position())
  }
}
