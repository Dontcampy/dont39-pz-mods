package com.dont39.pz.tunnel.transport.kcp

import com.dont39.pz.tunnel.LinkStats
import com.dont39.pz.tunnel.OpenTunnel
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * The client end: RakNet sends to 127.0.0.1 on the game port it was given (channel 0) and on the server's UDPPort
 * (channel 1), and the session carries both to the server over KCP.
 */
internal class KcpClient private constructor(private val session: KcpSession, params: KcpParams) : OpenTunnel {
  override val details = params.toString()

  override fun stats(): LinkStats = session.stats()

  override fun close(reason: String) = session.close(reason)

  companion object {
    private const val UDP_BUFFER = 1 shl 20

    /**
     * Sends HELLOs to [host]:[port] every [attemptMillis] until the server answers or [totalMillis] pass, then listens
     * locally on [gamePort] and the server's UDPPort.
     */
    fun open(host: String, port: Int, gamePort: Int, attemptMillis: Int, totalMillis: Int, log: TunnelLog): KcpClient {
      val transport = KcpTransport.name
      val target = "$host:$port"
      val address = try {
        InetAddress.getByName(host)
      } catch (e: IOException) {
        throw TunnelIo.probeFailed(transport, target, e)
      }
      TunnelIo.checkNotThisMachine(address, host, transport, target)
      val socket = try {
        DatagramSocket()
      } catch (e: IOException) {
        throw TunnelIo.probeFailed(transport, target, e)
      }
      val opened = mutableListOf<DatagramSocket>()
      try {
        val welcome = try {
          socket.receiveBufferSize = UDP_BUFFER
          socket.sendBufferSize = UDP_BUFFER
          // a closed port then fails the receive at once instead of timing out
          socket.connect(address, port)
          KcpWire.handshake(socket, Random.nextInt(), attemptMillis, totalMillis)
        } catch (e: IOException) {
          throw TunnelIo.probeFailed(transport, target, e)
        }
        socket.soTimeout = 0
        opened += TunnelIo.listenLocally(listOf(gamePort, welcome.udpPort), transport, target)
        val label = "KCP tunnel to $target"
        val scheduler = KcpScheduler("$label, ticks", KcpScheduler.period(welcome.params), log)
        val session = KcpSession(
          label,
          welcome.conv,
          welcome.params,
          opened,
          listOf(null, null),
          { datagram, length -> socket.send(DatagramPacket(datagram, length)) },
          log,
        ) {
          scheduler.stop()
          socket.close()
        }
        scheduler.add(session)
        session.start()
        thread(isDaemon = true, name = "${TunnelIo.THREAD_PREFIX}$label, KCP to UDP") { receive(socket, session) }
        scheduler.start()
        return KcpClient(session, welcome.params)
      } catch (t: Throwable) {
        runCatching { socket.close() }
        opened.forEach { runCatching { it.close() } }
        throw t
      }
    }

    private fun receive(socket: DatagramSocket, session: KcpSession) {
      val buffer = ByteArray(TunnelIo.MAX_DATAGRAM)
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        while (true) {
          packet.setData(buffer, 0, buffer.size)
          socket.receive(packet)
          val length = packet.length
          // e.g. a WELCOME to a repeated HELLO
          if (length < KcpWire.CONV_SIZE || KcpWire.conv(buffer, 0) != session.conv) {
            session.drop()
            continue
          }
          session.input(buffer, 0, length)
        }
      } catch (e: IOException) {
        // also how close() ends this thread; a closed session ignores it
        session.close(TunnelIo.describe(e))
      } catch (t: Throwable) {
        session.fail("receiving on the KCP tunnel", t)
      }
    }
  }
}
