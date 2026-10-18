package com.dont39.pz.tunnel

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals

private const val TIMEOUT_MILLIS = 5000

/** A tunnel end's log, kept for the test. */
internal class RecordingLog : TunnelLog {
  val lines = CopyOnWriteArrayList<String>()
  private val errors = CopyOnWriteArrayList<Throwable>()

  override fun info(message: () -> String) {
    lines += message()
  }

  override fun error(what: String, t: Throwable) {
    errors += AssertionError(what, t)
  }

  /** The first line that starts with [prefix], waiting for it. */
  fun await(prefix: String): String {
    val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000L
    while (System.nanoTime() < deadline) {
      lines.firstOrNull { it.startsWith(prefix) }?.let { return it }
      Thread.sleep(10)
    }
    throw AssertionError("no log line \"$prefix...\" in $lines")
  }

  fun assertNoErrors() {
    errors.firstOrNull()?.let { throw it }
  }
}

/**
 * RakNet's side of a loopback tunnel: the client's one socket, and the server's game port and UDPPort (channels 0 and
 * 1). Every datagram it waits for must arrive within the timeout.
 */
internal class LoopbackGame : AutoCloseable {
  val client = socket()
  val server = List(TunnelIo.CHANNELS) { socket() }

  /** Where the server-side tunnel relays to, per channel. */
  val targets: List<InetSocketAddress> = server.map { it.localSocketAddress as InetSocketAddress }

  /**
   * Sends datagrams of several sizes from the client to [clientPorts] (the client end, per channel) and replies from
   * the server, on each channel, and checks they arrive in order, intact and from the client end's port.
   */
  fun exchange(clientPorts: List<Int>) {
    for (channel in 0 until TunnelIo.CHANNELS) {
      val requests = messages(channel)
      requests.forEach { client.send(DatagramPacket(it, it.size, TunnelIo.LOOPBACK, clientPorts[channel])) }
      var relay: SocketAddress? = null
      requests.forEach { expected ->
        val packet = receive(server[channel])
        assertArrayEquals(expected, packet.data.copyOf(packet.length))
        relay = packet.socketAddress
      }
      val replies = messages(channel + TunnelIo.CHANNELS)
      replies.forEach { server[channel].send(DatagramPacket(it, it.size, relay)) }
      replies.forEach { expected ->
        val packet = receive(client)
        assertArrayEquals(expected, packet.data.copyOf(packet.length))
        assertEquals(clientPorts[channel], (packet.socketAddress as InetSocketAddress).port, "the reply's source port")
      }
    }
  }

  override fun close() {
    client.close()
    server.forEach { it.close() }
  }

  private fun socket() = DatagramSocket(InetSocketAddress(TunnelIo.LOOPBACK, 0)).apply {
    receiveBufferSize = 1 shl 20
    soTimeout = TIMEOUT_MILLIS
  }

  private fun receive(socket: DatagramSocket): DatagramPacket =
    DatagramPacket(ByteArray(TunnelIo.MAX_DATAGRAM), TunnelIo.MAX_DATAGRAM).also { socket.receive(it) }

  // from one byte up to several KCP fragments
  private fun messages(stream: Int): List<ByteArray> = (0 until 40).map { i ->
    val size = listOf(1, 100, 1400, 8000)[i % 4]
    ByteArray(size) { (stream * 101 + i * 31 + it).toByte() }
  }
}
