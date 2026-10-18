package com.dont39.pz.tunnel.fec

/**
 * FEC wire layout, after the transport's own header (big-endian):
 *
 * - data: `kind=DATA u8`, `seq u32`, one KCP packet
 * - parity: `kind=PARITY u8`, `seq u32`, group's first seq `u32`, `k u8`, parity index `u8`, coded shard
 *
 * Every datagram takes the next seq, so a group's data packets are `first until first + k` and its parity follows.
 * A coded data shard is `u16 length` + packet, zero-padded to the group's longest; that is what the parity covers.
 * Layout follows xtaci/kcp-go fec.go at f3f1bbd in spirit, not on the wire.
 */
internal object Fec {
  const val KIND_DATA = 0
  const val KIND_PARITY = 1

  /** Reserved for the transport's close message, never produced or accepted by the FEC layer. */
  const val KIND_CLOSE = 2

  const val DATA_HEADER = 5
  const val PARITY_HEADER = 11

  /** Most bytes a datagram adds to the KCP packet it carries; the KCP MTU must leave this room. */
  fun overhead(parityShards: Int): Int = if (parityShards == 0) DATA_HEADER else PARITY_HEADER + 2

  internal fun readInt(b: ByteArray, p: Int): Int =
    ((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
      ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)

  internal fun writeInt(b: ByteArray, p: Int, v: Int) {
    b[p] = (v ushr 24).toByte()
    b[p + 1] = (v ushr 16).toByte()
    b[p + 2] = (v ushr 8).toByte()
    b[p + 3] = v.toByte()
  }
}
