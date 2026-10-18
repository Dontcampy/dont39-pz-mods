package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.LoopbackGame
import com.dont39.pz.tunnel.RecordingLog
import com.dont39.pz.tunnel.TunnelIo
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Both ends of a TCP tunnel on 127.0.0.1, between a fake RakNet client and server. */
class RelayTest {
  private val game = LoopbackGame()
  private val clientLog = RecordingLog()
  private val serverLog = RecordingLog()
  private val clientUdp = List(TunnelIo.CHANNELS) { TunnelIo.datagramSocket(0) }
  private val serverUdp = game.targets.map { TunnelIo.datagramSocket(0).apply { connect(it) } }
  private val client: Relay
  private val server: Relay

  init {
    ServerSocket(0, 1, TunnelIo.LOOPBACK).use { listener ->
      val clientTcp = Socket(TunnelIo.LOOPBACK, listener.localPort)
      val serverTcp = listener.accept()
      client = Relay("tunnel to test", clientTcp, clientUdp, listOf(null, null), clientLog)
      server = Relay("tunnel from test", serverTcp, serverUdp, game.targets, serverLog)
    }
    client.start()
    server.start()
  }

  @AfterEach
  fun close() {
    client.close("the test ended")
    server.close("the test ended")
    game.close()
  }

  @Test
  fun `datagrams cross on both channels, both ways`() {
    game.exchange(clientUdp.map { it.localPort })
    clientLog.assertNoErrors()
    serverLog.assertNoErrors()
  }

  @Test
  fun `closing one end closes the other`() {
    game.exchange(clientUdp.map { it.localPort })
    client.close("the player left")
    clientLog.await("tunnel to test closed: the player left")
    serverLog.await("tunnel from test closed: closed by the other end")
    assertTrue((clientUdp + serverUdp).all(DatagramSocket::isClosed), "local sockets closed")
    clientLog.assertNoErrors()
    serverLog.assertNoErrors()
  }
}
