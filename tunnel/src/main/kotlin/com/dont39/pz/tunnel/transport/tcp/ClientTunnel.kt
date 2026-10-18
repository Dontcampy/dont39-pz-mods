package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.OpenTunnel
import com.dont39.pz.tunnel.TunnelFailed
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * The client end: RakNet sends to 127.0.0.1 on the game port it was given (channel 0) and on the server's UDPPort
 * (channel 1), and the relay carries both to the server over TCP.
 */
internal class ClientTunnel private constructor(private val relay: Relay) : OpenTunnel {
  override fun close(reason: String) = relay.close(reason)

  companion object {
    /**
     * Connects to [host]:[tcpPort] and exchanges hellos, each step within its timeout, then listens locally on
     * [gamePort] and the server's UDPPort.
     */
    fun open(
      host: String,
      tcpPort: Int,
      gamePort: Int,
      connectMillis: Int,
      helloMillis: Int,
      log: TunnelLog,
    ): ClientTunnel {
      val socket = Socket()
      val opened = mutableListOf<DatagramSocket>()
      try {
        try {
          Wire.configure(socket)
          socket.connect(InetSocketAddress(host, tcpPort), connectMillis)
        } catch (e: IOException) {
          throw TunnelFailed("no TCP tunnel at $host:$tcpPort (${TunnelIo.describe(e)})", e)
        }
        val local = try {
          onThisMachine(socket)
        } catch (e: IOException) {
          throw TunnelFailed("could not check whether $host is this machine (${TunnelIo.describe(e)})", e)
        }
        // binding the server's ports on 127.0.0.1 would succeed and take the server relay's datagrams, a loop
        if (local) {
          throw TunnelFailed("the server at $host runs on this machine, where the tunnel cannot listen on its ports")
        }
        val udpPort = try {
          Wire.clientHello(socket, helloMillis)
        } catch (e: IOException) {
          throw TunnelFailed("no TCP tunnel at $host:$tcpPort (${TunnelIo.describe(e)})", e)
        }
        for (port in listOf(gamePort, udpPort)) {
          try {
            opened += TunnelIo.datagramSocket(port)
          } catch (e: IOException) {
            val why = TunnelIo.describe(e)
            throw TunnelFailed("could not listen on 127.0.0.1:$port for the TCP tunnel at $host:$tcpPort ($why)", e)
          }
        }
        val relay = try {
          Relay("tunnel to $host:$tcpPort", socket, opened, listOf(null, null), log)
        } catch (e: IOException) {
          throw TunnelFailed("the TCP tunnel at $host:$tcpPort closed (${TunnelIo.describe(e)})", e)
        }
        val tunnel = ClientTunnel(relay)
        tunnel.relay.start()
        return tunnel
      } catch (t: Throwable) {
        runCatching { socket.close() }
        opened.forEach { runCatching { it.close() } }
        throw t
      }
    }

    private fun onThisMachine(socket: Socket): Boolean {
      val address = socket.inetAddress
      return address.isLoopbackAddress || address.isAnyLocalAddress ||
        NetworkInterface.getByInetAddress(address) != null
    }
  }
}
