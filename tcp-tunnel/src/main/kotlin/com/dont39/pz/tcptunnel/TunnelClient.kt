package com.dont39.pz.tcptunnel

import zombie.core.raknet.UdpEngine
import zombie.core.znet.SteamUtils

/** The client's one tunnel, opened for each connect to an address with a stored TCP port. */
internal object TunnelClient : TunnelLog {
  /** Where RakNet connects while a tunnel is up; the port stays the one it was given. */
  const val LOCAL_HOST = "127.0.0.1"

  // the probe runs on the game thread, which freezes the menu for at most both
  private const val CONNECT_MILLIS = 1000
  private const val HELLO_MILLIS = 1000

  private var engine: UdpEngine? = null
  private var tunnel: ClientTunnel? = null

  /** True when [engine] connects to [host]:[port] through a tunnel. */
  @Synchronized
  fun open(engine: UdpEngine, host: String?, port: Int): Boolean {
    close("the game started another connection")
    // Connect takes a Steam ID there, not an address
    if (host == null || (port == 0 && SteamUtils.isSteamModeEnabled())) {
      return false
    }
    val tcpPort = TunnelStore.get(host, port) ?: return false
    val opened = try {
      ClientTunnel.open(host.trim(), tcpPort, port, CONNECT_MILLIS, HELLO_MILLIS, this)
    } catch (e: ClientTunnel.Failed) {
      TcpTunnel.log { "${e.message}; connecting to $host:$port over UDP" }
      return false
    }
    this.engine = engine
    tunnel = opened
    TcpTunnel.log { "connecting to $host:$port through the TCP tunnel on port $tcpPort" }
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

  override fun info(message: () -> String) = TcpTunnel.log(message)

  override fun error(what: String, t: Throwable) = TcpTunnel.disable(what, t)
}
