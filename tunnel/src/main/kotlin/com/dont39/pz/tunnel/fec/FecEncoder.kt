package com.dont39.pz.tunnel.fec

import com.dont39.pz.tunnel.fec.rs.ReedSolomon

/**
 * Sender side of the FEC layer. Not thread-safe: driven under the session's lock, from the KCP output callback
 * ([encode]) and the scheduler tick ([flushDue]).
 *
 * Each datagram given to [sink] starts with [headroom] zero bytes for the transport's header; the sink may fill them
 * and owns the array afterwards.
 */
internal class FecEncoder(
  private val dataShards: Int,
  private val parityShards: Int,
  private val flushMillis: Int,
  private val headroom: Int,
  private val sink: (ByteArray) -> Unit,
) {
  init {
    require(dataShards in 1..255 && parityShards >= 0 && dataShards + parityShards <= 256) {
      "bad FEC shards $dataShards/$parityShards"
    }
    require(flushMillis > 0) { "bad FEC flush $flushMillis ms" }
  }

  private val codecs = arrayOfNulls<ReedSolomon>(dataShards + 1)
  private val group = ArrayList<ByteArray>(dataShards)
  private var groupFirst = 0
  private var groupStartedAt = 0L
  private var nextSeq = 0

  /** Sends one KCP packet as a data datagram; closes the group when it is full. */
  fun encode(packet: ByteArray, offset: Int, length: Int, nowMillis: Long) {
    require(length in 0..0xffff) { "packet of $length bytes" }
    val seq = nextSeq++
    val datagram = ByteArray(headroom + Fec.DATA_HEADER + length)
    datagram[headroom] = Fec.KIND_DATA.toByte()
    Fec.writeInt(datagram, headroom + 1, seq)
    packet.copyInto(datagram, headroom + Fec.DATA_HEADER, offset, offset + length)
    sink(datagram)
    if (parityShards == 0) return

    if (group.isEmpty()) {
      groupFirst = seq
      groupStartedAt = nowMillis
    }
    group += packet.copyOfRange(offset, offset + length)
    if (group.size == dataShards) closeGroup()
  }

  /** Closes a part-filled group whose wait is over, so low packet rates get parity too. */
  fun flushDue(nowMillis: Long) {
    if (group.isNotEmpty() && nowMillis - groupStartedAt >= flushMillis) closeGroup()
  }

  private fun closeGroup() {
    val k = group.size
    val codec = codecs[k] ?: ReedSolomon.create(k, parityShards).also { codecs[k] = it }
    val shardLen = group.maxOf { it.size } + 2
    val shards = Array(k + parityShards) { ByteArray(shardLen) }
    group.forEachIndexed { i, packet ->
      shards[i][0] = (packet.size ushr 8).toByte()
      shards[i][1] = packet.size.toByte()
      packet.copyInto(shards[i], 2)
    }
    codec.encodeParity(shards, 0, shardLen)
    for (index in 0 until parityShards) {
      val datagram = ByteArray(headroom + Fec.PARITY_HEADER + shardLen)
      datagram[headroom] = Fec.KIND_PARITY.toByte()
      Fec.writeInt(datagram, headroom + 1, nextSeq++)
      Fec.writeInt(datagram, headroom + 5, groupFirst)
      datagram[headroom + 9] = k.toByte()
      datagram[headroom + 10] = index.toByte()
      shards[k + index].copyInto(datagram, headroom + Fec.PARITY_HEADER)
      sink(datagram)
    }
    group.clear()
  }
}
