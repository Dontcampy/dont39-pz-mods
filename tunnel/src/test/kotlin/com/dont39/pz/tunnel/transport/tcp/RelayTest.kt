package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.LoopbackGame
import com.dont39.pz.tunnel.RecordingLog
import com.dont39.pz.tunnel.TunnelIo
import java.net.ServerSocket
import java.net.Socket
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Both ends of a TCP tunnel on 127.0.0.1, between a fake RakNet client and server. */
class RelayTest {
  private val game = LoopbackGame()
  private val clientLog = RecordingLog()
  private val serverLog = RecordingLog()
  private val clientEnd = game.clientEnd()
  private val serverEnd = game.serverEnd()
  private val client: Relay
  private val server: Relay

  init {
    ServerSocket(0, 1, TunnelIo.LOOPBACK).use { listener ->
      val clientTcp = Socket(TunnelIo.LOOPBACK, listener.localPort)
      val serverTcp = listener.accept()
      client = Relay("tunnel to test", clientTcp, clientEnd, clientLog)
      server = Relay("tunnel from test", serverTcp, serverEnd, serverLog)
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
    game.exchange(clientEnd)
    clientLog.assertNoErrors()
    serverLog.assertNoErrors()
  }

  @Test
  fun `closing one end closes the other`() {
    game.exchange(clientEnd)
    client.close("the player left")
    clientLog.await("tunnel to test closed: the player left")
    serverLog.await("tunnel from test closed: closed by the other end")
    LoopbackGame.assertClosed(clientEnd, serverEnd)
    clientLog.assertNoErrors()
    serverLog.assertNoErrors()
  }
}
