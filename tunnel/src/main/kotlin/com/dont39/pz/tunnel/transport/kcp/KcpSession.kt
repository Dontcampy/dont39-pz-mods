package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.common.describe
import com.dont39.pz.tunnel.LinkStats
import com.dont39.pz.tunnel.LocalChannels
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.fec.Fec
import com.dont39.pz.tunnel.fec.FecDecoder
import com.dont39.pz.tunnel.fec.FecEncoder
import com.dont39.pz.tunnel.kcp.Kcp
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One tunnel's KCP conversation [conv], either end: game datagrams from the [local] end go into KCP and FEC and out
 * through [send]; datagrams from the other end, given to [input], come back out to RakNet. [onClose] runs once when
 * the session closes, for whatever the caller keeps around it.
 */
internal class KcpSession(
  private val label: String,
  val conv: Int,
  private val params: KcpParams,
  private val local: LocalChannels,
  private val send: (ByteArray, Int) -> Unit,
  private val log: TunnelLog,
  private val onClose: (KcpSession) -> Unit,
) {
  private companion object {
    // RakNet pings a live connection every few seconds
    const val IDLE_MILLIS = 60_000L

    // a spoofed or abandoned HELLO should not hold sockets for the full idle time
    const val FIRST_IDLE_MILLIS = 10_000L

    // bounds memory when the other end stops acknowledging but has not hit the dead link yet
    const val WAITING_PER_WINDOW = 4

    const val CLOSE_REPEATS = 3

    fun clock(): Long = System.nanoTime() / 1_000_000L
  }

  private val closed = AtomicBoolean()

  // everything below is guarded by lock; KCP and FEC are not thread-safe
  private val lock = Any()
  private var now = clock()
  private val openedAt = now
  private var lastHeard = now
  private var heard = false
  private var dropped = 0L

  private val encoder = FecEncoder(params.dataShards, params.parityShards, params.flushMillis, KcpWire.CONV_SIZE) {
    KcpWire.writeConv(it, conv)
    send(it, it.size)
  }
  private val kcp = Kcp(conv) { buffer, length -> encoder.encode(buffer, 0, length, now) }.apply {
    setNoDelay(1, params.intervalMillis, params.resend, params.noCongestion)
    setWindow(params.window, params.window)
    setMtu(params.mtu - KcpWire.CONV_SIZE - Fec.overhead(params.parityShards))
  }
  private val decoder = FecDecoder(params.dataShards, params.parityShards) { buffer, offset, length ->
    if (!kcp.input(buffer, offset, length, now)) dropped++
  }
  private val maxWaiting = WAITING_PER_WINDOW * params.window

  fun start() {
    synchronized(lock) { kcp.update(clock()) }
    repeat(TunnelIo.CHANNELS) { channel ->
      thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$label, channel $channel to KCP") {
        guarded("relaying the $label") { fromLocal(channel) }
      }
    }
  }

  /** One datagram from the other end, its conv already matched. */
  fun input(datagram: ByteArray, offset: Int, length: Int) {
    if (KcpWire.isClose(datagram, offset, length)) {
      close("closed by the other end", notify = false, error = null)
      return
    }
    guarded("relaying the $label") {
      synchronized(lock) {
        if (closed.get()) return
        now = clock()
        lastHeard = now
        heard = true
        decoder.decode(datagram, offset + KcpWire.CONV_SIZE, length - KcpWire.CONV_SIZE, now)
        deliver()
      }
    }
  }

  /** A datagram for this end that is not part of the conversation. */
  fun drop() {
    synchronized(lock) { dropped++ }
  }

  /** KCP's timers and the FEC flush; the scheduler calls it at least every KCP interval. */
  fun tick() {
    val why = guarded("relaying the $label") {
      synchronized(lock) {
        if (closed.get()) return
        now = clock()
        kcp.update(now)
        encoder.flushDue(now)
        when {
          kcp.isDead -> "the other end stopped acknowledging"
          !heard && now - openedAt >= FIRST_IDLE_MILLIS ->
            "nothing from the other end within ${FIRST_IDLE_MILLIS / 1000} s of opening"
          now - lastHeard >= IDLE_MILLIS -> "nothing from the other end for ${IDLE_MILLIS / 1000} s"
          else -> null
        }
      }
    }
    why?.let { close(it) }
  }

  fun close(reason: String) = close(reason, notify = true, error = null)

  /** Not a network failure, while doing [what]. */
  fun fail(what: String, t: Throwable) = close(what, notify = true, error = t)

  // with an error, reason says what failed
  private fun close(reason: String, notify: Boolean, error: Throwable?) {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    // best effort: the other end's idle timeout covers a lost CLOSE
    if (notify) {
      val bye = KcpWire.close(conv)
      repeat(CLOSE_REPEATS) { runCatching { send(bye, bye.size) } }
    }
    local.close()
    onClose(this)
    val counts = synchronized(lock) { counts() }
    if (error == null) {
      log.info { "$label closed: $reason ($counts)" }
    } else {
      log.error("$reason ($counts)", error)
    }
  }

  /** The link counters so far; any thread may call it. */
  fun stats(): LinkStats = synchronized(lock) {
    LinkStats(
      segmentsSent = kcp.segmentsSent,
      retransmitsTimeout = kcp.timeoutRetransmits,
      retransmitsFast = kcp.fastRetransmits,
      seqExpected = decoder.seqExpected,
      seqMissing = decoder.seqMissing,
      fecRecovered = decoder.recovered,
      fecUnrecoverable = decoder.unrecoverable,
      srttMillis = kcp.srtt,
    )
  }

  private fun counts(): String {
    val fec = if (params.parityShards == 0) {
      ""
    } else {
      "FEC rebuilt ${decoder.recovered}, could not rebuild ${decoder.unrecoverable}; "
    }
    return "${fec}KCP resent ${kcp.timeoutRetransmits} on timeout, ${kcp.fastRetransmits} fast; " +
      "dropped ${dropped + decoder.malformed} invalid"
  }

  private fun fromLocal(channel: Int) {
    // the channel byte goes in front of the datagram, so each message is one send
    local.receiveLoop(channel, 1) { message, length ->
      message[0] = channel.toByte()
      val waiting = synchronized(lock) {
        // closing closed the sockets, which ends the loop at the next receive
        if (closed.get()) return@receiveLoop
        now = clock()
        kcp.send(message, 0, 1 + length)
        kcp.flush(now)
        kcp.waitSnd()
      }
      if (waiting > maxWaiting) {
        close("the other end does not keep up, $waiting packets wait to be sent")
      }
    }
  }

  private fun deliver() {
    while (true) {
      val message = kcp.receive() ?: return
      val channel = if (message.isEmpty()) -1 else message[0].toInt() and 0xff
      if (channel !in 0 until TunnelIo.CHANNELS || message.size < 2) {
        throw ProtocolException("received a message of ${message.size} bytes on channel $channel")
      }
      local.deliver(channel, message, 1, message.size - 1)
    }
  }

  private inline fun <T> guarded(what: String, body: () -> T): T? = try {
    body()
  } catch (e: IOException) {
    // a closed session's sockets end its blocking calls this way
    close(describe(e))
    null
  } catch (t: Throwable) {
    fail(what, t)
    null
  }
}
