package com.dont39.pz.tunnel.fec

import com.dont39.pz.tunnel.fec.rs.ReedSolomon
import kotlin.math.min

/**
 * Receiver side of the FEC layer. Not thread-safe: driven under the session's lock.
 *
 * Every data packet goes to [sink] at once (`buffer, offset, length`; the buffer may be reused after the call), so
 * FEC never delays delivery; a missing one is rebuilt and handed over as soon as its group holds `k` shards.
 */
internal class FecDecoder(
  private val dataShards: Int,
  private val parityShards: Int,
  private val sink: (ByteArray, Int, Int) -> Unit,
) {
  private class Received(val packet: ByteArray, val at: Long)

  private class Group(val first: Int, val k: Int, val shardLen: Int, parityShards: Int, val at: Long) {
    val parity = arrayOfNulls<ByteArray>(parityShards)
    var done = false
  }

  private val codecs = arrayOfNulls<ReedSolomon>(dataShards + 1)

  // insertion order is arrival order, which pruning relies on
  private val received = LinkedHashMap<Int, Received>()
  private val groups = LinkedHashMap<Int, Group>()
  private val maxReceived = MAX_GROUPS * (dataShards + parityShards)

  /** Data packets rebuilt from parity. */
  var recovered = 0L
    private set

  /** Groups that aged out with data missing and too few shards to rebuild it. */
  var unrecoverable = 0L
    private set
  var malformed = 0L
    private set

  /** Sequence numbers the other end has used so far, data and parity alike: the base of inbound loss. */
  var seqExpected = 0L
    private set

  /** Sequence numbers skipped and not arrived since; rebuilt packets stay counted, as the link lost them. */
  var seqMissing = 0L
    private set

  // the other end's encoder starts at 0
  private var highestSeq = -1

  // skipped sequence numbers, oldest first, so a late arrival is taken back out of seqMissing
  private val gaps = LinkedHashSet<Int>()

  // a datagram this far behind is past any recovery, and late enough to count as lost
  private val maxGaps = maxReceived

  /** Takes one datagram (after the transport's header); false, and counted, when it is not valid FEC. */
  fun decode(datagram: ByteArray, offset: Int, length: Int, nowMillis: Long): Boolean {
    prune(nowMillis)
    if (length < Fec.DATA_HEADER) return bad()
    val seq = Fec.readInt(datagram, offset + 1)
    return when (datagram[offset].toInt()) {
      Fec.KIND_DATA -> {
        track(seq)
        val start = offset + Fec.DATA_HEADER
        val size = length - Fec.DATA_HEADER
        if (parityShards == 0) {
          sink(datagram, start, size)
        } else if (!received.containsKey(seq)) {
          received[seq] = Received(datagram.copyOfRange(start, start + size), nowMillis)
          sink(datagram, start, size)
          groups.values.firstOrNull { !it.done && seq - it.first in 0 until it.k }?.let { tryRecover(it, nowMillis) }
        }
        true
      }
      Fec.KIND_PARITY -> parity(datagram, offset, length, seq, nowMillis)
      else -> bad()
    }
  }

  private fun parity(datagram: ByteArray, offset: Int, length: Int, seq: Int, nowMillis: Long): Boolean {
    if (parityShards == 0 || length < Fec.PARITY_HEADER + 2) return bad()
    val first = Fec.readInt(datagram, offset + 5)
    val k = datagram[offset + 9].toInt() and 0xff
    val index = datagram[offset + 10].toInt() and 0xff
    val shardLen = length - Fec.PARITY_HEADER
    if (k !in 1..dataShards || index >= parityShards || seq - first != k + index) return bad()
    val group = groups.getOrPut(first) { Group(first, k, shardLen, parityShards, nowMillis) }
    if (group.k != k || group.shardLen != shardLen) return bad()
    track(seq)
    if (group.done) return true
    if (group.parity[index] != null) return true
    val start = offset + Fec.PARITY_HEADER
    group.parity[index] = datagram.copyOfRange(start, start + shardLen)
    tryRecover(group, nowMillis)
    return true
  }

  private fun tryRecover(group: Group, nowMillis: Long) {
    val k = group.k
    val data = Array(k) { received[group.first + it]?.packet }
    val dataPresent = data.count { it != null }
    if (dataPresent == k) {
      finish(group)
      return
    }
    if (dataPresent + group.parity.count { it != null } < k) return

    val shardLen = group.shardLen
    val shards = Array(k + parityShards) { ByteArray(shardLen) }
    val present = BooleanArray(k + parityShards)
    data.forEachIndexed { i, packet ->
      if (packet == null) return@forEachIndexed
      // a data packet longer than the group's shards contradicts the parity: give the group up
      if (packet.size + 2 > shardLen) {
        malformed++
        finish(group)
        return
      }
      shards[i][0] = (packet.size ushr 8).toByte()
      shards[i][1] = packet.size.toByte()
      packet.copyInto(shards[i], 2)
      present[i] = true
    }
    group.parity.forEachIndexed { i, shard ->
      if (shard != null) {
        shards[k + i] = shard
        present[k + i] = true
      }
    }
    val codec = codecs[k] ?: ReedSolomon.create(k, parityShards).also { codecs[k] = it }
    codec.decodeMissing(shards, present, 0, shardLen)
    finish(group)

    for (i in 0 until k) {
      if (present[i]) continue
      val size = ((shards[i][0].toInt() and 0xff) shl 8) or (shards[i][1].toInt() and 0xff)
      if (size + 2 > shardLen) {
        malformed++
        continue
      }
      val packet = shards[i].copyOfRange(2, 2 + size)
      received[group.first + i] = Received(packet, nowMillis)
      recovered++
      sink(packet, 0, size)
    }
  }

  private fun track(seq: Int) {
    val ahead = seq - highestSeq
    if (ahead <= 0) {
      if (gaps.remove(seq)) seqMissing--
      return
    }
    seqExpected += ahead
    seqMissing += ahead - 1
    val kept = min(ahead - 1, maxGaps)
    repeat(kept) { gaps += seq - kept + it }
    highestSeq = seq
    val oldest = gaps.iterator()
    while (gaps.size > maxGaps) {
      oldest.next()
      oldest.remove()
    }
  }

  private fun finish(group: Group) {
    group.done = true
    group.parity.fill(null)
  }

  private fun prune(nowMillis: Long) {
    val packets = received.values.iterator()
    while (packets.hasNext()) {
      val next = packets.next()
      if (received.size <= maxReceived && nowMillis - next.at < MAX_AGE_MILLIS) break
      packets.remove()
    }
    val open = groups.values.iterator()
    while (open.hasNext()) {
      val next = open.next()
      if (groups.size <= MAX_GROUPS && nowMillis - next.at < MAX_AGE_MILLIS) break
      if (!next.done) unrecoverable++
      open.remove()
    }
  }

  private fun bad(): Boolean {
    malformed++
    return false
  }

  private companion object {
    // recovery after this is late: KCP has retransmitted by then
    const val MAX_AGE_MILLIS = 1000L
    const val MAX_GROUPS = 32
  }
}
