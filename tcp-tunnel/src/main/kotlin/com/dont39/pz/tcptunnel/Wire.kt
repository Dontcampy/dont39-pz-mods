package com.dont39.pz.tcptunnel

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket

/**
 * The tunnel's TCP protocol: the client's hello, the server's hello with its UDPPort, then every datagram as a frame of
 * a channel byte, a u16 big-endian length and its bytes.
 */
internal object Wire {
  private const val VERSION = 1
  private val MAGIC = "D39T".toByteArray(Charsets.US_ASCII)
  private val HELLO = MAGIC + VERSION.toByte()

  /** The server's game port (DefaultPort). */
  const val GAME_CHANNEL = 0

  /** The server's UDPPort, which a Steam-mode client connects RakNet to. */
  const val UDP_PORT_CHANNEL = 1
  const val CHANNELS = 2

  /** The largest UDP payload; a frame announcing more, or nothing, ends the tunnel. */
  const val MAX_DATAGRAM = 65507

  const val THREAD_PREFIX = "Dont39AllInOne tcp-tunnel: "

  // RakNet pings a live connection every few seconds (UdpEngine turns on its occasional ping)
  private const val IDLE_MILLIS = 60_000

  // map downloads arrive in bursts
  private const val UDP_BUFFER = 1 shl 20

  /** RakNet listens on IPv4; the JVM's loopback address may be IPv6. */
  val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

  fun configure(socket: Socket) {
    socket.tcpNoDelay = true
    socket.keepAlive = true
  }

  /** On 127.0.0.1; port 0 for any. */
  fun datagramSocket(port: Int): DatagramSocket = DatagramSocket(InetSocketAddress(LOOPBACK, port)).apply {
    receiveBufferSize = UDP_BUFFER
    sendBufferSize = UDP_BUFFER
  }

  /** The client speaks first, so the server stays silent towards anything that is not a tunnel client. */
  fun clientHello(socket: Socket, timeoutMillis: Int): Int {
    socket.soTimeout = timeoutMillis
    socket.getOutputStream().write(HELLO)
    // unbuffered: the relay reads the frames after it from the same stream
    val input = DataInputStream(socket.getInputStream())
    check(read(input), "the server")
    val udpPort = input.readUnsignedShort()
    socket.soTimeout = IDLE_MILLIS
    return udpPort
  }

  fun serverHello(socket: Socket, timeoutMillis: Int, udpPort: Int) {
    socket.soTimeout = timeoutMillis
    val hello = read(DataInputStream(socket.getInputStream()))
    // answered on another version too, so the client can name both
    if (hello.copyOf(MAGIC.size).contentEquals(MAGIC)) {
      DataOutputStream(socket.getOutputStream()).run {
        write(HELLO)
        writeShort(udpPort)
      }
    }
    check(hello, "the client")
    socket.soTimeout = IDLE_MILLIS
  }

  private fun read(input: DataInputStream): ByteArray = ByteArray(HELLO.size).also { input.readFully(it) }

  private fun check(hello: ByteArray, peer: String) {
    if (!hello.copyOf(MAGIC.size).contentEquals(MAGIC)) {
      throw ProtocolException("$peer is not a TCP tunnel of this mod")
    }
    val version = hello[MAGIC.size].toInt() and 0xFF
    if (version != VERSION) {
      throw ProtocolException("$peer speaks tunnel protocol version $version, this mod version $VERSION")
    }
  }

  fun describe(e: IOException): String = when (e) {
    is EOFException -> "closed by the other end"
    else -> e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
  }
}
