package com.dont39.pz.tcptunnel

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ProtocolException
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.thread

/** Where the tunnel reports: the feature's log in game. */
internal interface TunnelLog {
  fun info(message: () -> String)

  /** Not a network failure. */
  fun error(what: String, t: Throwable)
}

/**
 * Moves datagrams between the [udp] sockets, one per channel, and the tunnel's [tcp] connection until either side fails
 * or [close] is called, then reports once that the tunnel ([label]) closed. [peers]: where RakNet listens, per channel;
 * null on the client, which learns them from the datagrams RakNet sends on each channel.
 */
internal class Relay(
  private val label: String,
  private val tcp: Socket,
  private val udp: List<DatagramSocket>,
  peers: List<SocketAddress?>,
  private val log: TunnelLog,
) {
  private val peers = AtomicReferenceArray(peers.toTypedArray())
  private val closed = AtomicBoolean()
  private val out = tcp.getOutputStream()

  init {
    require(udp.size == Wire.CHANNELS && peers.size == Wire.CHANNELS) { "one UDP socket and peer per channel" }
  }

  fun start() {
    udp.forEachIndexed { channel, socket ->
      thread(isDaemon = true, name = "${Wire.THREAD_PREFIX}$label, channel $channel to TCP") {
        relay { udpToTcp(channel, socket) }
      }
    }
    thread(isDaemon = true, name = "${Wire.THREAD_PREFIX}$label, TCP to UDP") { relay { tcpToUdp() } }
  }

  fun close(reason: String) = close(reason, null)

  private fun close(reason: String, error: Throwable?) {
    if (!closed.compareAndSet(false, true)) {
      return
    }
    // also ends the other threads' blocking calls
    runCatching { tcp.close() }
    udp.forEach { runCatching { it.close() } }
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
      close(Wire.describe(e), null)
    } catch (t: Throwable) {
      close("an unexpected error", t)
    }
  }

  private fun udpToTcp(channel: Int, socket: DatagramSocket) {
    // the header goes in front of the datagram, so each frame is one write
    val frame = ByteArray(3 + Wire.MAX_DATAGRAM)
    frame[0] = channel.toByte()
    val packet = DatagramPacket(frame, 3, Wire.MAX_DATAGRAM)
    while (true) {
      packet.setData(frame, 3, Wire.MAX_DATAGRAM)
      socket.receive(packet)
      // the latest sender: Steam's server query may come from another socket than RakNet
      peers.set(channel, packet.socketAddress)
      val length = packet.length
      // frames are never empty, and an empty datagram carries nothing
      if (length == 0) {
        continue
      }
      frame[1] = (length ushr 8).toByte()
      frame[2] = length.toByte()
      synchronized(out) {
        out.write(frame, 0, 3 + length)
      }
    }
  }

  private fun tcpToUdp() {
    val input = DataInputStream(BufferedInputStream(tcp.getInputStream()))
    val datagram = ByteArray(Wire.MAX_DATAGRAM)
    val packet = DatagramPacket(datagram, 0)
    while (true) {
      val channel = input.readUnsignedByte()
      val length = input.readUnsignedShort()
      if (channel >= Wire.CHANNELS) {
        throw ProtocolException("received a frame on unknown channel $channel")
      }
      if (length == 0 || length > Wire.MAX_DATAGRAM) {
        throw ProtocolException("received a frame of $length bytes")
      }
      input.readFully(datagram, 0, length)
      // RakNet's client always sends first, so this is only a stray frame before it did
      val to = peers[channel] ?: continue
      packet.setData(datagram, 0, length)
      packet.socketAddress = to
      udp[channel].send(packet)
    }
  }
}
