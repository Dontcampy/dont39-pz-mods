/*
 * KCP - A Better ARQ Protocol Implementation
 * Kotlin port of skywind3000/kcp ikcp.c at master b1a7a21 (2026-06-23).
 * Copyright (c) 2017 Lin Wei (skywind3000 at gmail.com). MIT License: META-INF/licenses/kcp-LICENSE.txt.
 *
 * Left out of the port: stream mode, pluggable congestion control (ccops), logging and allocator hooks.
 * IKCP_FASTACK_CONSERVE is on, as in ikcp.c. Changed: input and flush take the current time, as kcp-go does; with
 * ikcp's clock from the last update, RTT samples and resend times were off by up to one interval, which caused
 * spurious timeout retransmits in the loss harness.
 */
package com.dont39.pz.tunnel.kcp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One KCP endpoint in message mode. Not thread-safe: the caller confines it to one lock.
 *
 * [output] gets each packet to send; its buffer is reused after the call returns. Times are milliseconds; only their
 * low 32 bits matter, as in `ikcp.c`, so any monotonic clock works.
 */
internal class Kcp(val conv: Int, private val output: (ByteArray, Int) -> Unit) {
  private class Segment(val data: ByteArray) {
    var frg = 0
    var wnd = 0
    var ts = 0
    var sn = 0
    var una = 0
    var resendts = 0
    var rto = 0
    var fastack = 0
    var xmit = 0
  }

  private var sndUna = 0
  private var sndNxt = 0
  private var rcvNxt = 0
  private var tsProbe = 0
  private var probeWait = 0
  private var sndWnd = WND_SND
  private var rcvWnd = WND_RCV
  private var rmtWnd = WND_RCV
  private var cwnd = 0
  private var incr = 0
  private var probe = 0
  private var mtu = MTU_DEF
  private var mss = mtu - OVERHEAD
  private var buffer = ByteArray((mtu + OVERHEAD) * 3)
  private var state = 0

  private val sndQueue = ArrayDeque<Segment>()
  private val rcvQueue = ArrayDeque<Segment>()
  private val sndBuf = ArrayDeque<Segment>()
  private val rcvBuf = ArrayDeque<Segment>()

  private var acklist = IntArray(16)
  private var ackCount = 0

  private var rxSrtt = 0
  private var rxRttval = 0
  private var rxRto = RTO_DEF
  private var rxMinrto = RTO_MIN
  private var current = 0
  private var interval = INTERVAL
  private var tsFlush = INTERVAL
  private var nodelay = 0
  private var updated = false
  private var ssthresh = THRESH_INIT
  private var fastresend = 0
  private var fastlimit = FASTACK_LIMIT
  private var nocwnd = false
  private var deadLink = DEADLINK

  /** Segments sent for the first time; the base of the retransmit rate. */
  var segmentsSent = 0L
    private set

  /** Retransmits after a timeout (`kcp->xmit` in ikcp.c). */
  var timeoutRetransmits = 0L
    private set

  /** Retransmits triggered by skipped acks; not counted by ikcp.c. */
  var fastRetransmits = 0L
    private set

  /** Smoothed round-trip time in milliseconds; 0 before the first ack. */
  val srtt: Int get() = rxSrtt

  /** A segment was sent [DEADLINK] times without an ack. */
  val isDead: Boolean get() = state == -1

  /** Segments not yet acknowledged by the peer, queued or in flight. */
  fun waitSnd(): Int = sndBuf.size + sndQueue.size

  /** Size of the next whole message, or -1 when none is complete. */
  fun peekSize(): Int {
    val first = rcvQueue.firstOrNull() ?: return -1
    if (first.frg == 0) return first.data.size
    if (rcvQueue.size < first.frg + 1) return -1
    var length = 0
    for (seg in rcvQueue) {
      length += seg.data.size
      if (seg.frg == 0) break
    }
    return length
  }

  /** The next whole message, or null. */
  fun receive(): ByteArray? {
    val size = peekSize()
    if (size < 0) return null
    val recover = rcvQueue.size >= rcvWnd
    val message = ByteArray(size)
    var pos = 0
    while (true) {
      val seg = rcvQueue.removeFirst()
      seg.data.copyInto(message, pos)
      pos += seg.data.size
      if (seg.frg == 0) break
    }
    moveReceived()
    // the window opened again: tell the peer at the next flush
    if (rcvQueue.size < rcvWnd && recover) probe = probe or ASK_TELL
    return message
  }

  /** Queues one message; it arrives whole or not at all. */
  fun send(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
    require(length >= 0) { "negative length $length" }
    val count = if (length <= mss) 1 else (length + mss - 1) / mss
    require(count < WND_RCV) { "message of $length bytes needs $count fragments, limit ${WND_RCV - 1}" }
    var pos = offset
    var left = length
    for (i in 0 until count) {
      val size = min(left, mss)
      val seg = Segment(data.copyOfRange(pos, pos + size))
      seg.frg = count - i - 1
      sndQueue.addLast(seg)
      pos += size
      left -= size
    }
  }

  private fun updateAck(rtt: Int) {
    if (rxSrtt == 0) {
      rxSrtt = rtt
      rxRttval = rtt / 2
    } else {
      val delta = abs(rtt - rxSrtt)
      rxRttval = (3 * rxRttval + delta) / 4
      rxSrtt = (7 * rxSrtt + rtt) / 8
      if (rxSrtt < 1) rxSrtt = 1
    }
    val rto = rxSrtt + max(interval, 4 * rxRttval)
    rxRto = min(max(rxMinrto, rto), RTO_MAX)
  }

  private fun shrinkBuf() {
    sndUna = sndBuf.firstOrNull()?.sn ?: sndNxt
  }

  private fun parseAck(sn: Int) {
    if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return
    for (i in sndBuf.indices) {
      val seg = sndBuf[i]
      if (sn == seg.sn) {
        sndBuf.removeAt(i)
        break
      }
      if (diff(sn, seg.sn) < 0) break
    }
  }

  private fun parseUna(una: Int) {
    while (sndBuf.isNotEmpty() && diff(una, sndBuf.first().sn) > 0) sndBuf.removeFirst()
  }

  private fun parseFastack(sn: Int, ts: Int) {
    if (diff(sn, sndUna) < 0 || diff(sn, sndNxt) >= 0) return
    for (seg in sndBuf) {
      if (diff(sn, seg.sn) < 0) break
      if (sn != seg.sn && diff(ts, seg.ts) >= 0) seg.fastack++
    }
  }

  private fun ackPush(sn: Int, ts: Int) {
    if (ackCount * 2 + 2 > acklist.size) acklist = acklist.copyOf(acklist.size * 2)
    acklist[ackCount * 2] = sn
    acklist[ackCount * 2 + 1] = ts
    ackCount++
  }

  private fun parseData(newseg: Segment) {
    val sn = newseg.sn
    if (diff(sn, rcvNxt + rcvWnd) >= 0 || diff(sn, rcvNxt) < 0) return
    var i = rcvBuf.size - 1
    var repeat = false
    while (i >= 0) {
      val other = rcvBuf[i].sn
      if (other == sn) {
        repeat = true
        break
      }
      if (diff(sn, other) > 0) break
      i--
    }
    if (!repeat) rcvBuf.add(i + 1, newseg)
    moveReceived()
  }

  private fun moveReceived() {
    while (rcvBuf.isNotEmpty()) {
      val seg = rcvBuf.first()
      if (seg.sn != rcvNxt || rcvQueue.size >= rcvWnd) break
      rcvBuf.removeFirst()
      rcvQueue.addLast(seg)
      rcvNxt++
    }
  }

  /** Feeds one packet from the peer; false when it is not a valid packet of this conversation. */
  fun input(data: ByteArray, offset: Int, length: Int, nowMillis: Long): Boolean {
    current = nowMillis.toInt()
    val prevUna = sndUna
    var maxack = 0
    var latestTs = 0
    var flag = false
    if (length < OVERHEAD) return false

    var pos = offset
    var size = length
    while (size >= OVERHEAD) {
      if (readInt(data, pos) != conv) return false
      val cmd = data[pos + 4].toInt() and 0xff
      val frg = data[pos + 5].toInt() and 0xff
      val wnd = (data[pos + 6].toInt() and 0xff) or ((data[pos + 7].toInt() and 0xff) shl 8)
      val ts = readInt(data, pos + 8)
      val sn = readInt(data, pos + 12)
      val una = readInt(data, pos + 16)
      val len = readInt(data, pos + 20)
      pos += OVERHEAD
      size -= OVERHEAD
      if (len < 0 || size < len) return false
      if (cmd < CMD_PUSH || cmd > CMD_WINS) return false

      rmtWnd = wnd
      parseUna(una)
      shrinkBuf()
      when (cmd) {
        CMD_ACK -> {
          if (diff(current, ts) >= 0) updateAck(diff(current, ts))
          parseAck(sn)
          shrinkBuf()
          if (!flag) {
            flag = true
            maxack = sn
            latestTs = ts
          } else if (diff(sn, maxack) > 0 && diff(ts, latestTs) > 0) {
            maxack = sn
            latestTs = ts
          }
        }
        CMD_PUSH -> if (diff(sn, rcvNxt + rcvWnd) < 0) {
          ackPush(sn, ts)
          if (diff(sn, rcvNxt) >= 0) {
            val seg = Segment(data.copyOfRange(pos, pos + len))
            seg.frg = frg
            seg.wnd = wnd
            seg.ts = ts
            seg.sn = sn
            seg.una = una
            parseData(seg)
          }
        }
        CMD_WASK -> probe = probe or ASK_TELL
      }
      pos += len
      size -= len
    }

    if (flag) parseFastack(maxack, latestTs)

    if (diff(sndUna, prevUna) > 0 && cwnd < rmtWnd) {
      val mss = mss
      if (cwnd < ssthresh) {
        cwnd++
        incr += mss
      } else {
        if (incr < mss) incr = mss
        incr += (mss * mss) / incr + (mss / 16)
        if ((cwnd + 1) * mss <= incr) cwnd = (incr + mss - 1) / max(mss, 1)
      }
      if (cwnd > rmtWnd) {
        cwnd = rmtWnd
        incr = rmtWnd * mss
      }
    }
    return true
  }

  private fun encodeHeader(pos: Int, cmd: Int, frg: Int, wnd: Int, ts: Int, sn: Int, una: Int, len: Int) {
    writeInt(buffer, pos, conv)
    buffer[pos + 4] = cmd.toByte()
    buffer[pos + 5] = frg.toByte()
    buffer[pos + 6] = wnd.toByte()
    buffer[pos + 7] = (wnd ushr 8).toByte()
    writeInt(buffer, pos + 8, ts)
    writeInt(buffer, pos + 12, sn)
    writeInt(buffer, pos + 16, una)
    writeInt(buffer, pos + 20, len)
  }

  private fun wndUnused(): Int = if (rcvQueue.size < rcvWnd) rcvWnd - rcvQueue.size else 0

  /** Sends pending acks, new data and due retransmits now, e.g. right after [send]; [update] calls it on its interval. */
  fun flush(nowMillis: Long) {
    current = nowMillis.toInt()
    flush()
  }

  private fun flush() {
    if (!updated) return
    val current = current
    val priorCwnd = cwnd
    val wnd = wndUnused()
    var ptr = 0
    var change = 0
    var lost = false

    fun room(need: Int) {
      if (ptr + need > mtu) {
        output(buffer, ptr)
        ptr = 0
      }
    }

    for (i in 0 until ackCount) {
      room(OVERHEAD)
      encodeHeader(ptr, CMD_ACK, 0, wnd, acklist[i * 2 + 1], acklist[i * 2], rcvNxt, 0)
      ptr += OVERHEAD
    }
    ackCount = 0

    // probe window size (if remote window size equals zero)
    if (rmtWnd == 0) {
      if (probeWait == 0) {
        probeWait = PROBE_INIT
        tsProbe = current + probeWait
      } else if (diff(current, tsProbe) >= 0) {
        if (probeWait < PROBE_INIT) probeWait = PROBE_INIT
        probeWait += probeWait / 2
        if (probeWait > PROBE_LIMIT) probeWait = PROBE_LIMIT
        tsProbe = current + probeWait
        probe = probe or ASK_SEND
      }
    } else {
      tsProbe = 0
      probeWait = 0
    }

    if (probe and ASK_SEND != 0) {
      room(OVERHEAD)
      encodeHeader(ptr, CMD_WASK, 0, wnd, 0, 0, rcvNxt, 0)
      ptr += OVERHEAD
    }
    if (probe and ASK_TELL != 0) {
      room(OVERHEAD)
      encodeHeader(ptr, CMD_WINS, 0, wnd, 0, 0, rcvNxt, 0)
      ptr += OVERHEAD
    }
    probe = 0

    var window = min(sndWnd, rmtWnd)
    if (!nocwnd) window = min(cwnd, window)

    while (diff(sndNxt, sndUna + window) < 0) {
      val seg = sndQueue.removeFirstOrNull() ?: break
      sndBuf.addLast(seg)
      seg.wnd = wnd
      seg.ts = current
      seg.sn = sndNxt++
      seg.una = rcvNxt
      seg.resendts = current
      seg.rto = rxRto
      seg.fastack = 0
      seg.xmit = 0
    }

    val resent = if (fastresend > 0) fastresend else Int.MAX_VALUE
    val rtomin = if (nodelay == 0) rxRto shr 3 else 0

    for (segment in sndBuf) {
      var needsend = false
      if (segment.xmit == 0) {
        needsend = true
        segment.xmit++
        segmentsSent++
        segment.rto = rxRto
        segment.resendts = current + segment.rto + rtomin
      } else if (diff(current, segment.resendts) >= 0) {
        needsend = true
        segment.xmit++
        timeoutRetransmits++
        if (nodelay == 0) {
          segment.rto += max(segment.rto, rxRto)
        } else {
          val step = if (nodelay < 2) segment.rto else rxRto
          segment.rto += step / 2
        }
        segment.resendts = current + segment.rto
        lost = true
      } else if (segment.fastack >= resent) {
        if (segment.xmit <= fastlimit || fastlimit <= 0) {
          needsend = true
          segment.xmit++
          segment.fastack = 0
          segment.resendts = current + segment.rto
          change++
          fastRetransmits++
        }
      }

      if (needsend) {
        segment.ts = current
        segment.wnd = wnd
        segment.una = rcvNxt
        room(OVERHEAD + segment.data.size)
        encodeHeader(ptr, CMD_PUSH, segment.frg, wnd, current, segment.sn, rcvNxt, segment.data.size)
        ptr += OVERHEAD
        segment.data.copyInto(buffer, ptr)
        ptr += segment.data.size
        if (segment.xmit >= deadLink) state = -1
      }
    }

    if (ptr > 0) output(buffer, ptr)

    if (change > 0) {
      val inflight = sndNxt - sndUna
      ssthresh = max(inflight / 2, THRESH_MIN)
      cwnd = ssthresh + resent
      incr = cwnd * mss
    }
    if (lost) {
      ssthresh = max(priorCwnd / 2, THRESH_MIN)
      cwnd = 1
      incr = mss
    }
    if (cwnd < 1) {
      cwnd = 1
      incr = mss
    }
  }

  /** Call repeatedly, every [setNoDelay] interval. */
  fun update(nowMillis: Long) {
    current = nowMillis.toInt()
    if (!updated) {
      updated = true
      tsFlush = current
    }
    var slap = diff(current, tsFlush)
    if (slap >= 10000 || slap < -10000) {
      tsFlush = current
      slap = 0
    }
    if (slap >= 0) {
      tsFlush += interval
      if (diff(current, tsFlush) >= 0) tsFlush = current + interval
      flush()
    }
  }

  /** Largest packet [output] gets, KCP header included. */
  fun setMtu(mtu: Int) {
    require(mtu >= 50 && mtu >= OVERHEAD) { "mtu $mtu below 50" }
    buffer = ByteArray((mtu + OVERHEAD) * 3)
    this.mtu = mtu
    mss = mtu - OVERHEAD
  }

  /** `ikcp_nodelay`: negative arguments keep the current value. */
  fun setNoDelay(nodelay: Int, interval: Int, resend: Int, noCongestion: Boolean) {
    if (nodelay >= 0) {
      this.nodelay = nodelay
      rxMinrto = if (nodelay != 0) RTO_NDL else RTO_MIN
    }
    if (interval >= 0) this.interval = interval.coerceIn(10, 5000)
    if (resend >= 0) fastresend = resend
    nocwnd = noCongestion
  }

  /** Send and receive window in segments; the receive window is at least [WND_RCV]. */
  fun setWindow(sndWnd: Int, rcvWnd: Int) {
    if (sndWnd > 0) this.sndWnd = sndWnd
    if (rcvWnd > 0) this.rcvWnd = max(rcvWnd, WND_RCV)
  }

  companion object {
    const val OVERHEAD = 24
    private const val RTO_NDL = 30
    private const val RTO_MIN = 100
    private const val RTO_DEF = 200
    private const val RTO_MAX = 60000
    private const val CMD_PUSH = 81
    private const val CMD_ACK = 82
    private const val CMD_WASK = 83
    private const val CMD_WINS = 84
    private const val ASK_SEND = 1
    private const val ASK_TELL = 2
    private const val WND_SND = 32
    const val WND_RCV = 128
    private const val MTU_DEF = 1400
    private const val INTERVAL = 100
    const val DEADLINK = 20
    private const val THRESH_INIT = 2
    private const val THRESH_MIN = 2
    private const val PROBE_INIT = 5000
    private const val PROBE_LIMIT = 120000
    private const val FASTACK_LIMIT = 5

    /** The conversation id of a packet, for demultiplexing before [input]. */
    fun conv(data: ByteArray, offset: Int = 0): Int = readInt(data, offset)

    private fun diff(later: Int, earlier: Int): Int = later - earlier

    // ikcp's own wire format is little-endian
    private fun readInt(b: ByteArray, p: Int): Int =
      (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
        ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)

    private fun writeInt(b: ByteArray, p: Int, v: Int) {
      b[p] = v.toByte()
      b[p + 1] = (v ushr 8).toByte()
      b[p + 2] = (v ushr 16).toByte()
      b[p + 3] = (v ushr 24).toByte()
    }
  }
}
