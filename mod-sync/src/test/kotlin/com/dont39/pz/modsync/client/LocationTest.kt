package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.protocol.Protocol
import com.dont39.pz.modsync.protocol.Trailer
import java.net.URI
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocationTest {
  // what connection-details holds before the trailer
  private val vanilla = ByteArray(200) { (it * 7).toByte() }

  private fun packet(trailer: ByteArray): ByteBuffer {
    val b = ByteBuffer.allocate(vanilla.size + trailer.size)
    b.put(vanilla).put(trailer)
    b.limit(b.position()).position(0)
    return b
  }

  @Test
  fun `the base address is built from the host and port`() {
    assertEquals(URI("http://example.com:16300/dont39-modsync/v1/"), Location.base(" example.com ", 16300))
    assertEquals(URI("http://10.0.0.5:16300/dont39-modsync/v1/"), Location.base("10.0.0.5", 16300))
    assertEquals(URI("http://[2001:db8::1]:16300/dont39-modsync/v1/"), Location.base("2001:db8::1", 16300))
    assertEquals(URI("http://[2001:db8::1]:16300/dont39-modsync/v1/"), Location.base("[2001:db8::1]", 16300))
    assertEquals(URI("http://[fe80::1%25eth0]:16300/dont39-modsync/v1/"), Location.base("fe80::1%eth0", 16300))
    assertEquals(
      URI("http://example.com:16300/dont39-modsync/v1/manifest.json"),
      Location.base("example.com", 16300).resolve(Protocol.MANIFEST),
    )
  }

  @Test
  fun `a host that cannot be downloaded from is refused`() {
    assertThrows<IllegalArgumentException> { Location.base("", 16300) }
    assertThrows<IllegalArgumentException> { Location.base("  ", 16300) }
    assertThrows<IllegalArgumentException> { Location.base("my_host", 16300) }
    assertThrows<IllegalArgumentException> { Location.base("a b", 16300) }
  }

  @Test
  fun `the location combines trailer and host`() {
    val b = packet(Trailer.encode(16300))
    assertEquals(Location.At(URI("http://example.com:16300/dont39-modsync/v1/")), Location.of(b, "example.com"))
    assertTrue(Location.of(b, null) is Location.None)
    assertTrue(Location.of(b, "") is Location.None)
    assertEquals(Location.None("the server sent no mod sync location"), Location.of(packet(ByteArray(0)), "a"))
  }
}
