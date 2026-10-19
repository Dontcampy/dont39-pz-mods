package com.dont39.pz.tunnel.transport.tcp

import com.dont39.pz.tunnel.LocalChannels
import com.dont39.pz.tunnel.OpenTunnel
import com.dont39.pz.tunnel.TunnelIo
import com.dont39.pz.tunnel.TunnelLog
import com.dont39.pz.tunnel.closeQuietly
import java.io.IOException
import java.net.InetSocketAddress
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
      val target = "$host:$tcpPort"
      val socket = Socket()
      var local: LocalChannels? = null
      try {
        try {
          Wire.configure(socket)
          socket.connect(InetSocketAddress(host, tcpPort), connectMillis)
        } catch (e: IOException) {
          throw TunnelIo.probeFailed(TcpTransport.name, target, e)
        }
        TunnelIo.checkNotThisMachine(socket.inetAddress, host, TcpTransport.name, target)
        val udpPort = try {
          Wire.clientHello(socket, helloMillis)
        } catch (e: IOException) {
          throw TunnelIo.probeFailed(TcpTransport.name, target, e)
        }
        val channels = LocalChannels.listen(gamePort, udpPort, TcpTransport.name, target).also { local = it }
        val relay = try {
          Relay("tunnel to $target", socket, channels, log)
        } catch (e: IOException) {
          throw TunnelIo.probeFailed(TcpTransport.name, target, e)
        }
        val tunnel = ClientTunnel(relay)
        tunnel.relay.start()
        return tunnel
      } catch (t: Throwable) {
        socket.closeQuietly()
        local?.close()
        throw t
      }
    }
  }
}
