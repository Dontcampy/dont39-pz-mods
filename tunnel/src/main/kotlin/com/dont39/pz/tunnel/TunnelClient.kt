package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.transport.tcp.TcpTransport
import zombie.core.raknet.UdpEngine
import zombie.core.znet.SteamUtils

/** The client's one tunnel, opened for each connect to an address with a stored tunnel port. */
internal object TunnelClient : TunnelLog {
  /** Where RakNet connects while a tunnel is up; the port stays the one it was given. */
  const val LOCAL_HOST = "127.0.0.1"

  private var engine: UdpEngine? = null
  private var tunnel: OpenTunnel? = null

  /** True when [engine] connects to [host]:[port] through a tunnel. */
  @Synchronized
  fun open(engine: UdpEngine, host: String?, port: Int): Boolean {
    close("the game started another connection")
    // Connect takes a Steam ID there, not an address
    if (host == null || (port == 0 && SteamUtils.isSteamModeEnabled())) {
      return false
    }
    // the store holds only TCP ports so far
    val transport: Transport = TcpTransport
    val tunnelPort = TunnelStore.get(host, port) ?: return false
    val opened = try {
      transport.open(host.trim(), tunnelPort, port, this)
    } catch (e: TunnelFailed) {
      Tunnel.log { "${e.message}; connecting to $host:$port over UDP" }
      return false
    }
    this.engine = engine
    tunnel = opened
    Tunnel.log { "connecting to $host:$port through the ${transport.name} tunnel on port $tunnelPort" }
    return true
  }

  @Synchronized
  fun shutdown(engine: UdpEngine) {
    // an older engine may shut down after the next connect started
    if (engine === this.engine) {
      close("the game closed the connection")
    }
  }

  private fun close(reason: String) {
    tunnel?.close(reason)
    tunnel = null
    engine = null
  }

  override fun info(message: () -> String) = Tunnel.log(message)

  override fun error(what: String, t: Throwable) = Tunnel.disable(what, t)
}
