package com.dont39.pz.serverreborn.network.protocol

import java.nio.ByteBuffer

/**
 * The record a server with the mod appends to the connection-details it writes, `[payload][int length][long MAGIC]`,
 * as Dont39 All-in-One's mod sync does with its own. Vanilla clients stop reading before it. Payload version 1 is
 * `[byte TRAILER_VERSION][byte protocol version]`.
 *
 * Mod sync reads only the record at the very end, so ours goes before a mod sync record already there, and readers
 * here step over one.
 */
internal object Trailer {
  /** "D39SYNCG". */
  const val MAGIC = 0x4433_3953_594E_4347L

  /** Mod sync's "D39MSYNC". */
  const val MOD_SYNC_MAGIC = 0x4433_394D_5359_4E43L
  const val TRAILER_VERSION = 1

  private const val FOOTER = Int.SIZE_BYTES + Long.SIZE_BYTES
  private const val V1_SIZE = 2

  /** What a client read: the server's protocol version, or why there is none. */
  sealed interface Decoded {
    data class Offered(val protocol: Int) : Decoded

    data class None(val reason: String) : Decoded
  }

  /** Appends to the data in `[0, position)` of [out], before a mod sync record at its end. */
  fun append(out: ByteBuffer, protocol: Int) {
    // a duplicate is big-endian, as the game writes
    val b = out.duplicate()
    val end = b.position()
    val start = recordStart(b, end, MOD_SYNC_MAGIC)
    val after = ByteArray(end - start).also { b.get(start, it) }
    b.position(start)
    b.put(TRAILER_VERSION.toByte()).put(protocol.toByte()).putInt(V1_SIZE).putLong(MAGIC)
    b.put(after)
    out.position(b.position())
  }

  /** Reads the data in `[0, limit)` of [data]; leaves its position and limit as they are. */
  fun decode(data: ByteBuffer): Decoded {
    val b = data.duplicate()
    val end = recordStart(b, b.limit(), MOD_SYNC_MAGIC)
    if (end < FOOTER || b.getLong(end - Long.SIZE_BYTES) != MAGIC) {
      return Decoded.None("the server does not run Server Reborn")
    }
    val length = b.getInt(end - FOOTER)
    val start = end - FOOTER - length
    if (length < 1 || start < 0) {
      return Decoded.None("the server's Server Reborn record says it has $length bytes, which do not fit")
    }
    val version = b.get(start).toInt() and 0xFF
    if (version != TRAILER_VERSION) {
      return Decoded.None(
        "the server's Server Reborn record has version $version, this version of the mod reads $TRAILER_VERSION; " +
          "update Server Reborn on the side that is older",
      )
    }
    if (length != V1_SIZE) {
      return Decoded.None("the server's Server Reborn record has $length bytes instead of $V1_SIZE")
    }
    return Decoded.Offered(b.get(start + 1).toInt() and 0xFF)
  }

  /** Where a whole record with [magic] that ends at [end] starts, or [end] if there is none. */
  private fun recordStart(b: ByteBuffer, end: Int, magic: Long): Int {
    if (end < FOOTER || b.getLong(end - Long.SIZE_BYTES) != magic) {
      return end
    }
    val length = b.getInt(end - FOOTER)
    val start = end - FOOTER - length
    return if (length < 0 || start < 0) end else start
  }
}
