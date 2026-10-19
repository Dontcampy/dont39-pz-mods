package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.LoopbackGame
import com.dont39.pz.tunnel.RecordingLog
import com.dont39.pz.tunnel.TunnelIo
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Both ends of a KCP tunnel on 127.0.0.1, between a fake RakNet client and server. */
class KcpSessionTest {
  private companion object {
    const val CONV = 42
    val PARAMS = KcpParams(10, 2, true, 1024, 1350, 10, 3, 20)
  }

  private val game = LoopbackGame()
  private val clientLog = RecordingLog()
  private val serverLog = RecordingLog()
  private val clientNet = DatagramSocket(InetSocketAddress(TunnelIo.LOOPBACK, 0))
  private val serverNet = DatagramSocket(InetSocketAddress(TunnelIo.LOOPBACK, 0))
  private val clientEnd = game.clientEnd()
  private val serverEnd = game.serverEnd()
  private val scheduler = KcpScheduler("test ticks", KcpScheduler.period(PARAMS), clientLog)
  private val closed = CountDownLatch(2)

  private val client = KcpSession(
    "KCP tunnel to test",
    CONV,
    PARAMS,
    clientEnd,
    { datagram, length -> clientNet.send(DatagramPacket(datagram, length, serverNet.localSocketAddress)) },
    clientLog,
  ) { closed.countDown() }

  private val server = KcpSession(
    "KCP tunnel from test",
    CONV,
    PARAMS,
    serverEnd,
    { datagram, length -> serverNet.send(DatagramPacket(datagram, length, clientNet.localSocketAddress)) },
    serverLog,
  ) { closed.countDown() }

  init {
    receive(clientNet, client)
    receive(serverNet, server)
    listOf(client, server).forEach {
      scheduler.add(it)
      it.start()
    }
    scheduler.start()
  }

  @AfterEach
  fun close() {
    client.close("the test ended")
    server.close("the test ended")
    scheduler.stop()
    clientNet.close()
    serverNet.close()
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
    clientLog.await("KCP tunnel to test closed: the player left (")
    serverLog.await("KCP tunnel from test closed: closed by the other end (")
    assertTrue(closed.await(5, TimeUnit.SECONDS), "both sessions ran their close action")
    LoopbackGame.assertClosed(clientEnd, serverEnd)
    clientLog.assertNoErrors()
    serverLog.assertNoErrors()
  }

  // as KcpClient and KcpListener feed a session, with one conversation per socket
  private fun receive(socket: DatagramSocket, session: KcpSession) = thread(isDaemon = true) {
    val buffer = ByteArray(TunnelIo.MAX_DATAGRAM)
    val packet = DatagramPacket(buffer, buffer.size)
    try {
      while (true) {
        packet.setData(buffer, 0, buffer.size)
        socket.receive(packet)
        session.input(buffer, 0, packet.length)
      }
    } catch (_: IOException) {
      // closed by the test
    }
  }
}
