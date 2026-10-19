package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.NotATunnel
import com.dont39.pz.tunnel.OtherVersion
import com.dont39.pz.tunnel.TunnelIo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KcpWireTest {
  private companion object {
    const val NONCE = 0x1234abcd
    const val CONV = 42
    const val UDP_PORT = 16262
    const val ATTEMPT_MILLIS = 100
    const val TOTAL_MILLIS = 2000
    val PARAMS = KcpParams(10, 2, true, 1024, 1350, 10, 3, 20)
  }

  private val server = DatagramSocket(0, TunnelIo.LOOPBACK)
  private val client = DatagramSocket(0, TunnelIo.LOOPBACK).apply { connect(server.localSocketAddress) }

  @AfterEach
  fun close() {
    client.close()
    server.close()
  }

  /** Answers the first datagram with each of [answers], in order. */
  private fun answer(vararg answers: ByteArray): CompletableFuture<ByteArray> = CompletableFuture.supplyAsync {
    val packet = DatagramPacket(ByteArray(1500), 1500)
    server.receive(packet)
    answers.forEach { server.send(DatagramPacket(it, it.size, packet.socketAddress)) }
    packet.data.copyOf(packet.length)
  }

  @Test
  fun `a hello is read back with its version and nonce`() {
    val hello = KcpWire.hello(NONCE)
    assertEquals(0, KcpWire.conv(hello, 0))
    assertEquals(KcpWire.VERSION to NONCE, KcpWire.readHello(hello, 0, hello.size))
  }

  @Test
  fun `a hello of another version is read without its nonce`() {
    val hello = KcpWire.hello(NONCE).also { it[KcpWire.CONV_SIZE + 4] = 2 }
    assertEquals(2 to null, KcpWire.readHello(hello, 0, hello.size))
  }

  @Test
  fun `what is not a hello is not read as one`() {
    val other = ByteArray(16)
    assertNull(KcpWire.readHello(other, 0, other.size))
  }

  @Test
  fun `the handshake returns the welcome for its own hello`() {
    val earlier = KcpWire.welcome(NONCE + 1, CONV + 1, UDP_PORT + 1, PARAMS)
    val sent = answer(earlier, KcpWire.welcome(NONCE, CONV, UDP_PORT, PARAMS))
    val welcome = KcpWire.handshake(client, NONCE, ATTEMPT_MILLIS, TOTAL_MILLIS)
    assertEquals(CONV, welcome.conv)
    assertEquals(UDP_PORT, welcome.udpPort)
    assertEquals(PARAMS, welcome.params)
    val hello = sent.get(TOTAL_MILLIS.toLong(), TimeUnit.MILLISECONDS)
    assertEquals(KcpWire.VERSION to NONCE, KcpWire.readHello(hello, 0, hello.size))
  }

  @Test
  fun `the handshake names the server's other version`() {
    val other = ByteBuffer.allocate(KcpWire.CONV_SIZE + 5).putInt(0).put("D39K".toByteArray()).put(2.toByte()).array()
    answer(other)
    val e = assertThrows<OtherVersion> { KcpWire.handshake(client, NONCE, ATTEMPT_MILLIS, TOTAL_MILLIS) }
    assertEquals(2, e.theirs)
    assertEquals(KcpWire.VERSION, e.ours)
  }

  @Test
  fun `the handshake refuses a server that is not a tunnel`() {
    answer("not a tunnel".toByteArray())
    assertThrows<NotATunnel> { KcpWire.handshake(client, NONCE, ATTEMPT_MILLIS, TOTAL_MILLIS) }
  }

  @Test
  fun `the handshake gives up after its total time`() {
    val started = System.nanoTime()
    assertThrows<SocketTimeoutException> { KcpWire.handshake(client, NONCE, ATTEMPT_MILLIS, 300) }
    assertTrue(System.nanoTime() - started >= 300_000_000L)
  }

  @Test
  fun `a close is recognised and carries the conv`() {
    val close = KcpWire.close(CONV)
    assertEquals(CONV, KcpWire.conv(close, 0))
    assertTrue(KcpWire.isClose(close, 0, close.size))
  }
}
