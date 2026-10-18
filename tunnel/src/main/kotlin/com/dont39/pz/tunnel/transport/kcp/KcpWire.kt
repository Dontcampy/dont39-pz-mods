package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.NotATunnel
import com.dont39.pz.tunnel.OtherVersion
import com.dont39.pz.tunnel.fec.Fec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

/**
 * The KCP tunnel's datagrams, each starting with `conv u32` (big-endian, as all of this header):
 *
 * - HELLO, client: `conv=0`, `"D39K"`, version `u8`, nonce `u32`
 * - WELCOME, server: `conv=0`, `"D39K"`, version `u8`, nonce `u32`, conv `u32`, UDPPort `u16`, [KcpParams]; to a HELLO
 *   of another version only up to the version, so the client can name both
 * - data and parity: `conv`, then the FEC layout ([Fec]) around one KCP packet
 * - CLOSE: `conv`, `kind=CLOSE u8`
 *
 * Inside KCP, each message is a channel byte and one game datagram.
 */
internal object KcpWire {
  const val VERSION = 1
  private val MAGIC = "D39K".toByteArray(Charsets.US_ASCII)

  /** The conv in front of every datagram. */
  const val CONV_SIZE = 4

  private const val VERSION_END = CONV_SIZE + 4 + 1
  private const val HELLO_SIZE = VERSION_END + 4
  private const val WELCOME_SIZE = HELLO_SIZE + 4 + 2 + KcpParams.SIZE
  private const val CLOSE_SIZE = CONV_SIZE + 1

  /** What the server sent the client. */
  class Welcome(val conv: Int, val udpPort: Int, val params: KcpParams)

  fun hello(nonce: Int): ByteArray =
    ByteBuffer.allocate(HELLO_SIZE).putInt(0).put(MAGIC).put(VERSION.toByte()).putInt(nonce).array()

  fun welcome(nonce: Int, conv: Int, udpPort: Int, params: KcpParams): ByteArray =
    ByteBuffer.allocate(WELCOME_SIZE).putInt(0).put(MAGIC).put(VERSION.toByte()).putInt(nonce).putInt(conv)
      .putShort(udpPort.toShort()).also { params.write(it) }.array()

  /** The answer to a HELLO of another version. */
  fun otherVersion(): ByteArray = ByteBuffer.allocate(VERSION_END).putInt(0).put(MAGIC).put(VERSION.toByte()).array()

  fun close(conv: Int): ByteArray = ByteBuffer.allocate(CLOSE_SIZE).putInt(conv).put(Fec.KIND_CLOSE.toByte()).array()

  fun isClose(datagram: ByteArray, offset: Int, length: Int): Boolean =
    length == CLOSE_SIZE && datagram[offset + CONV_SIZE].toInt() == Fec.KIND_CLOSE

  fun conv(datagram: ByteArray, offset: Int): Int = Fec.readInt(datagram, offset)

  fun writeConv(datagram: ByteArray, conv: Int) = Fec.writeInt(datagram, 0, conv)

  /** A HELLO's version and nonce (nonce null when another version sent it), or null when it is not one. */
  fun readHello(datagram: ByteArray, offset: Int, length: Int): Pair<Int, Int?>? {
    if (length < VERSION_END || !hasMagic(datagram, offset)) return null
    val version = datagram[offset + VERSION_END - 1].toInt() and 0xff
    if (version != VERSION) return version to null
    if (length < HELLO_SIZE) return null
    return version to Fec.readInt(datagram, offset + VERSION_END)
  }

  /**
   * Sends HELLOs on [socket], connected to the server, every [attemptMillis] until a WELCOME for [nonce] arrives or
   * [totalMillis] pass. Throws [SocketTimeoutException], [NotATunnel], [OtherVersion] or another IO failure.
   */
  fun handshake(socket: DatagramSocket, nonce: Int, attemptMillis: Int, totalMillis: Int): Welcome {
    val hello = DatagramPacket(hello(nonce), HELLO_SIZE)
    val buffer = ByteArray(WELCOME_SIZE + 64)
    val reply = DatagramPacket(buffer, buffer.size)
    val deadline = System.nanoTime() + totalMillis * 1_000_000L
    while (true) {
      socket.send(hello)
      val attemptEnd = minOf(System.nanoTime() + attemptMillis * 1_000_000L, deadline)
      while (true) {
        val left = (attemptEnd - System.nanoTime()) / 1_000_000L
        if (left < 1) break
        socket.soTimeout = left.toInt()
        try {
          reply.setData(buffer, 0, buffer.size)
          socket.receive(reply)
        } catch (e: SocketTimeoutException) {
          break
        }
        readWelcome(buffer, reply.length, nonce)?.let { return it }
      }
      if (System.nanoTime() >= deadline) {
        throw SocketTimeoutException("no WELCOME within $totalMillis ms")
      }
    }
  }

  // null for a WELCOME to an earlier HELLO
  private fun readWelcome(b: ByteArray, length: Int, nonce: Int): Welcome? {
    if (length < VERSION_END || Fec.readInt(b, 0) != 0 || !hasMagic(b, 0)) {
      throw NotATunnel("the server is not a KCP tunnel of this mod")
    }
    val version = b[VERSION_END - 1].toInt() and 0xff
    if (version != VERSION) {
      throw OtherVersion("the server speaks tunnel protocol version $version, this mod version $VERSION", version, VERSION)
    }
    if (length < WELCOME_SIZE) throw NotATunnel("the server sent a WELCOME of $length bytes")
    val input = ByteBuffer.wrap(b, VERSION_END, length - VERSION_END)
    if (input.int != nonce) return null
    val conv = input.int
    val udpPort = input.short.toInt() and 0xffff
    val params = checkNotNull(KcpParams.read(input))
    if (conv == 0) throw ProtocolException("the server sent conv 0")
    params.problem()?.let { throw ProtocolException("the server sent settings out of range: $it") }
    return Welcome(conv, udpPort, params)
  }

  private fun hasMagic(b: ByteArray, offset: Int): Boolean =
    MAGIC.indices.all { b[offset + CONV_SIZE + it] == MAGIC[it] }
}
