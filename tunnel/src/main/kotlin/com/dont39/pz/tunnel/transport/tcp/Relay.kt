package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.LocalChannels
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.closeQuietly
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ProtocolException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Moves datagrams between the [local] end and the tunnel's [tcp] connection until either side fails or [close] is
 * called, then reports once that the tunnel ([label]) closed.
 */
internal class Relay(
  private val label: String,
  private val tcp: Socket,
  private val local: LocalChannels,
  private val log: TunnelLog,
) {
  private companion object {
    // the frame header (Wire)
    const val HEADER = 3
  }

  private val closed = AtomicBoolean()
  private val out = tcp.getOutputStream()

  fun start() {
    repeat(TunnelIo.CHANNELS) { channel ->
      thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$label, channel $channel to TCP") {
        relay { udpToTcp(channel) }
      }
    }
    thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$label, TCP to UDP") { relay { tcpToUdp() } }
  }

  fun close(reason: String) = close(reason, null)

  private fun close(reason: String, error: Throwable?) {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    // also ends the other threads' blocking calls
    tcp.closeQuietly()
    local.close()
    if (error == null) {
      log.info { "$label closed: $reason" }
    } else {
      log.error("relaying the $label", error)
    }
  }

  private inline fun relay(body: () -> Unit) {
    try {
      body()
    } catch (e: IOException) {
      close(TunnelIo.describe(e), null)
    } catch (t: Throwable) {
      close("an unexpected error", t)
    }
  }

  private fun udpToTcp(channel: Int) {
    // the header goes in front of the datagram, so each frame is one write
    local.receiveLoop(channel, HEADER) { frame, length ->
      frame[0] = channel.toByte()
      frame[1] = (length ushr 8).toByte()
      frame[2] = length.toByte()
      synchronized(out) {
        out.write(frame, 0, HEADER + length)
      }
    }
  }

  private fun tcpToUdp() {
    val input = DataInputStream(BufferedInputStream(tcp.getInputStream()))
    val datagram = ByteArray(TunnelIo.MAX_DATAGRAM)
    while (true) {
      val channel = input.readUnsignedByte()
      val length = input.readUnsignedShort()
      if (channel >= TunnelIo.CHANNELS) {
        throw ProtocolException("received a frame on unknown channel $channel")
      }
      if (length == 0 || length > TunnelIo.MAX_DATAGRAM) {
        throw ProtocolException("received a frame of $length bytes")
      }
      input.readFully(datagram, 0, length)
      local.deliver(channel, datagram, 0, length)
    }
  }
}
