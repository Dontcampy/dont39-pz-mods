package com.dont39.pz.tunnel

import java.io.EOFException
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/** What every transport shares: the channels it carries and the local UDP sockets RakNet talks to. */
internal object TunnelIo {
  /** The server's game port (DefaultPort). */
  const val GAME_CHANNEL = 0

  /** The server's UDPPort, which a Steam-mode client connects RakNet to. */
  const val UDP_PORT_CHANNEL = 1
  const val CHANNELS = 2

  /** The largest UDP payload. */
  const val MAX_DATAGRAM = 65507

  const val THREAD_PREFIX = "Dont39AllInOne tunnel: "

  // map downloads arrive in bursts
  private const val UDP_BUFFER = 1 shl 20

  /** RakNet listens on IPv4; the JVM's loopback address may be IPv6. */
  val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

  /** On 127.0.0.1; port 0 for any. */
  fun datagramSocket(port: Int): DatagramSocket = DatagramSocket(InetSocketAddress(LOOPBACK, port)).apply {
    receiveBufferSize = UDP_BUFFER
    sendBufferSize = UDP_BUFFER
  }

  fun describe(e: IOException): String = when (e) {
    is EOFException -> "closed by the other end"
    else -> e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
  }
}
