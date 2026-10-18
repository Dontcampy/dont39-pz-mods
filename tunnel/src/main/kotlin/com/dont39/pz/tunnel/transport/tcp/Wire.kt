package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.NotATunnel
import com.dont39.pz.tunnel.OtherVersion
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket

/**
 * The tunnel's TCP protocol: the client's hello, the server's hello with its UDPPort, then every datagram as a frame of
 * a channel byte, a u16 big-endian length and its bytes. A frame announcing more than a UDP payload, or nothing, ends
 * the tunnel.
 */
internal object Wire {
  private const val VERSION = 1
  private val MAGIC = "D39T".toByteArray(Charsets.US_ASCII)
  private val HELLO = MAGIC + VERSION.toByte()

  // RakNet pings a live connection every few seconds (UdpEngine turns on its occasional ping)
  private const val IDLE_MILLIS = 60_000

  fun configure(socket: Socket) {
    socket.tcpNoDelay = true
    socket.keepAlive = true
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
      throw NotATunnel("$peer is not a TCP tunnel of this mod")
    }
    val version = hello[MAGIC.size].toInt() and 0xFF
    if (version != VERSION) {
      throw OtherVersion("$peer speaks tunnel protocol version $version, this mod version $VERSION", version, VERSION)
    }
  }
}
