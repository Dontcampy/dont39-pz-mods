package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.NotATunnel
import com.dont39.pz.tunnel.OtherVersion
import com.dont39.pz.tunnel.TunnelIo
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WireTest {
  private companion object {
    const val TIMEOUT = 5000
    const val UDP_PORT = 16262
    val HELLO = "D39T".toByteArray(Charsets.US_ASCII) + 1.toByte()
  }

  private val server = ServerSocket(0, 1, TunnelIo.LOOPBACK)
  private val client = Socket(TunnelIo.LOOPBACK, server.localPort)
  private val accepted = server.accept()

  @AfterEach
  fun close() {
    listOf(client, accepted, server).forEach { it.close() }
  }

  @Test
  fun `hellos carry the server's UDP port`() {
    val serverSide = CompletableFuture.runAsync { Wire.serverHello(accepted, TIMEOUT, UDP_PORT) }
    assertEquals(UDP_PORT, Wire.clientHello(client, TIMEOUT))
    serverSide.get(TIMEOUT.toLong(), TimeUnit.MILLISECONDS)
  }

  @Test
  fun `the client names the server's other version`() {
    CompletableFuture.runAsync {
      DataOutputStream(accepted.getOutputStream()).run {
        write(HELLO.copyOf().also { it[4] = 2 })
        writeShort(UDP_PORT)
      }
    }
    val e = assertThrows<OtherVersion> { Wire.clientHello(client, TIMEOUT) }
    assertEquals(2, e.theirs)
    assertEquals(1, e.ours)
  }

  @Test
  fun `the server answers another version, then refuses it`() {
    client.getOutputStream().write(HELLO.copyOf().also { it[4] = 2 })
    val e = assertThrows<OtherVersion> { Wire.serverHello(accepted, TIMEOUT, UDP_PORT) }
    assertEquals(2, e.theirs)
    // so the client can name both versions
    val answer = ByteArray(HELLO.size).also { DataInputStream(client.getInputStream()).readFully(it) }
    assertArrayEquals(HELLO, answer)
  }

  @Test
  fun `the server stays silent towards what is not a tunnel`() {
    client.getOutputStream().write("GET /".toByteArray(Charsets.US_ASCII))
    assertThrows<NotATunnel> { Wire.serverHello(accepted, TIMEOUT, UDP_PORT) }
    accepted.close()
    assertEquals(-1, client.getInputStream().read())
  }

  @Test
  fun `the client refuses a server that is not a tunnel`() {
    accepted.getOutputStream().write("SSH-2".toByteArray(Charsets.US_ASCII))
    assertThrows<NotATunnel> { Wire.clientHello(client, TIMEOUT) }
  }
}
