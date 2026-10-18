package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.fec.Fec
import com.dont39.pz.tunnel.fec.FecDecoder
import com.dont39.pz.tunnel.fec.FecEncoder
import com.dont39.pz.tunnel.kcp.Kcp
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ProtocolException
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.thread

/**
 * One tunnel's KCP conversation [conv], either end: game datagrams from the [local] sockets (one per channel) go into
 * KCP and FEC and out through [send]; datagrams from the other end, given to [input], come back out to RakNet. [peers]:
 * where RakNet listens, per channel; null on the client, which learns them from the datagrams RakNet sends. [onClose]
 * runs once when the session closes, for whatever the caller keeps around it.
 */
internal class KcpSession(
  private val label: String,
  val conv: Int,
  private val params: KcpParams,
  private val local: List<DatagramSocket>,
  peers: List<SocketAddress?>,
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

  init {
    require(local.size == TunnelIo.CHANNELS && peers.size == TunnelIo.CHANNELS) { "one UDP socket and peer per channel" }
  }

  private val peers = AtomicReferenceArray(peers.toTypedArray())
  private val closed = AtomicBoolean()

  // everything below is guarded by lock; KCP and FEC are not thread-safe
  private val lock = Any()
  private var now = clock()
  private val openedAt = now
  private var lastHeard = now
  private var heard = false
  private var dropped = 0L
  private val toLocal = DatagramPacket(ByteArray(0), 0)

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
    local.forEachIndexed { channel, socket ->
      thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$label, channel $channel to KCP") {
        guarded("relaying the $label") { fromLocal(channel, socket) }
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
    // also ends the local readers' blocking receives
    local.forEach { runCatching { it.close() } }
    onClose(this)
    val counts = synchronized(lock) { counts() }
    if (error == null) {
      log.info { "$label closed: $reason ($counts)" }
    } else {
      log.error("$reason ($counts)", error)
    }
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

  private fun fromLocal(channel: Int, socket: DatagramSocket) {
    // the channel byte goes in front of the datagram, so each message is one send
    val message = ByteArray(1 + TunnelIo.MAX_DATAGRAM)
    message[0] = channel.toByte()
    val packet = DatagramPacket(message, 1, TunnelIo.MAX_DATAGRAM)
    while (true) {
      packet.setData(message, 1, TunnelIo.MAX_DATAGRAM)
      socket.receive(packet)
      // the latest sender: Steam's server query may come from another socket than RakNet
      peers.set(channel, packet.socketAddress)
      // an empty datagram carries nothing, and an empty message would be malformed
      if (packet.length == 0) {
        continue
      }
      val waiting = synchronized(lock) {
        if (closed.get()) return
        now = clock()
        kcp.send(message, 0, 1 + packet.length)
        kcp.flush(now)
        kcp.waitSnd()
      }
      if (waiting > maxWaiting) {
        close("the other end does not keep up, $waiting packets wait to be sent")
        return
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
      // RakNet's client always sends first, so this is only a stray message before it did
      val to = peers[channel] ?: continue
      toLocal.setData(message, 1, message.size - 1)
      toLocal.socketAddress = to
      local[channel].send(toLocal)
    }
  }

  private inline fun <T> guarded(what: String, body: () -> T): T? = try {
    body()
  } catch (e: IOException) {
    // a closed session's sockets end its blocking calls this way
    close(TunnelIo.describe(e))
    null
  } catch (t: Throwable) {
    fail(what, t)
    null
  }
}
