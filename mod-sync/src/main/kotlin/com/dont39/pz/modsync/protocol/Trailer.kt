package com.dont39.pz.modsync.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The record a serving server appends to every connection-details it writes, `[payload][int length][long MAGIC]`.
 * Vanilla clients stop reading before it; a client reads it backwards from the buffer's limit with absolute gets,
 * before the join reads the buffer itself. Payload version 1 is `[byte VERSION][unsigned short HTTP port]`.
 */
internal object Trailer {
  /** "D39MSYNC". */
  const val MAGIC = 0x4433394D53594E43L
  const val VERSION = 1

  private const val FOOTER = Int.SIZE_BYTES + Long.SIZE_BYTES
  private const val V1_SIZE = 1 + Short.SIZE_BYTES
  private const val MAX_PORT = 0xFFFF

  sealed interface Decoded {
    data class Port(val port: Int) : Decoded

    data class None(val reason: String) : Decoded
  }

  fun encode(port: Int): ByteArray {
    require(port in 1..MAX_PORT) { "port $port is not between 1 and $MAX_PORT" }
    return ByteBuffer.allocate(V1_SIZE + FOOTER)
      .put(VERSION.toByte())
      .putShort(port.toShort())
      .putInt(V1_SIZE)
      .putLong(MAGIC)
      .array()
  }

  /** Leaves [buffer]'s position, limit and order as they are. */
  fun decode(buffer: ByteBuffer): Decoded {
    val b = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
    val end = b.limit()
    if (end < FOOTER || b.getLong(end - Long.SIZE_BYTES) != MAGIC) {
      return Decoded.None("the server sent no mod sync location")
    }
    val length = b.getInt(end - FOOTER)
    val start = end - FOOTER - length
    if (length < 1 || start < 0) {
      return Decoded.None("the server's mod sync location says it has $length bytes, which do not fit")
    }
    val version = b.get(start).toInt() and 0xFF
    if (version != VERSION) {
      return Decoded.None(
        "the server's mod sync location has version $version, but this version of the mod reads only $VERSION; " +
          "update Dont39 All-in-One",
      )
    }
    if (length != V1_SIZE) {
      return Decoded.None("the server's mod sync location has $length bytes instead of $V1_SIZE")
    }
    val port = b.getShort(start + 1).toInt() and MAX_PORT
    if (port == 0) {
      return Decoded.None("the server's mod sync location has port 0")
    }
    return Decoded.Port(port)
  }
}
