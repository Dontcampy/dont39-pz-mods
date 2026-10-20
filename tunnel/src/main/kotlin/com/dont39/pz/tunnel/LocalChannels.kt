package com.dont39.pz.tunnel

import com.dont39.pz.common.describe
import com.dont39.pz.tunnel.TunnelFailed.Reason
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * A tunnel's local end, either transport: one UDP socket per channel towards RakNet, and where RakNet listens on each.
 * The client learns those from the datagrams RakNet sends ([listen]); the server knows the game's ports ([toGame]).
 */
internal class LocalChannels private constructor(
  private val sockets: List<DatagramSocket>,
  peers: List<SocketAddress?>,
) {
  init {
    require(sockets.size == TunnelIo.CHANNELS && peers.size == TunnelIo.CHANNELS) { "one UDP socket and peer per channel" }
  }

  private val peers = AtomicReferenceArray(peers.toTypedArray())

  /** On the server, RakNet and the admin tools see the player at this port of 127.0.0.1. */
  fun localPort(channel: Int): Int = sockets[channel].localPort

  /**
   * Receives on [channel] until its socket fails or is closed, giving each datagram to [onDatagram] as the buffer and
   * the datagram's length. The datagram starts after [headerBytes], so the caller can put its header in front and send
   * both as one.
   */
  fun receiveLoop(channel: Int, headerBytes: Int, onDatagram: (ByteArray, Int) -> Unit) {
    val buffer = ByteArray(headerBytes + TunnelIo.MAX_DATAGRAM)
    val packet = DatagramPacket(buffer, headerBytes, TunnelIo.MAX_DATAGRAM)
    while (true) {
      packet.setData(buffer, headerBytes, TunnelIo.MAX_DATAGRAM)
      sockets[channel].receive(packet)
      // the latest sender: Steam's server query may come from another socket than RakNet
      peers.set(channel, packet.socketAddress)
      // an empty datagram carries nothing, and the transports' empty frames or messages are malformed
      if (packet.length == 0) {
        continue
      }
      onDatagram(buffer, packet.length)
    }
  }

  /** Sends a datagram from the other end to RakNet on [channel]. */
  fun deliver(channel: Int, buffer: ByteArray, offset: Int, length: Int) {
    // RakNet's client always sends first, so this is only a stray datagram before it did
    val to = peers[channel] ?: return
    sockets[channel].send(DatagramPacket(buffer, offset, length, to))
  }

  /** Also ends the receive loops' blocking receives. */
  fun close() {
    sockets.forEach { it.closeQuietly() }
  }

  companion object {
    /**
     * The client's: listens on 127.0.0.1 at [gamePort] and [udpPort], where RakNet sends, for the [transport] tunnel
     * at [target]. Throws [TunnelFailed] when a port is taken.
     */
    fun listen(gamePort: Int, udpPort: Int, transport: String, target: String): LocalChannels {
      val opened = mutableListOf<DatagramSocket>()
      for (port in listOf(gamePort, udpPort)) {
        try {
          opened += TunnelIo.datagramSocket(port)
        } catch (e: IOException) {
          opened.forEach { it.closeQuietly() }
          val why = describe(e)
          throw TunnelFailed(
            Reason.LOCAL_PORT,
            listOf(port.toString(), why),
            "could not listen on 127.0.0.1:$port for the $transport tunnel at $target ($why)",
            e,
          )
        }
      }
      return LocalChannels(opened, listOf(null, null))
    }

    /** The server's: one socket per channel, connected to the game's [gamePort] and [udpPort] on 127.0.0.1. */
    fun toGame(gamePort: Int, udpPort: Int): LocalChannels {
      val targets = listOf(gamePort, udpPort).map { InetSocketAddress(TunnelIo.LOOPBACK, it) }
      val opened = mutableListOf<DatagramSocket>()
      try {
        targets.forEach { opened += TunnelIo.datagramSocket(0).apply { connect(it) } }
      } catch (e: IOException) {
        opened.forEach { it.closeQuietly() }
        throw e
      }
      return LocalChannels(opened, targets)
    }
  }
}
