package com.dont39.pz.tcptunnel

import java.io.IOException
import zombie.SandboxOptions
import zombie.config.IntegerConfigOption
import zombie.network.GameServer

/** The dedicated server's listener, opened on the TCP port of the mod's sandbox option. */
internal object TunnelServer : TunnelLog {
  private const val OPTION = "Dont39AllInOne_TcpTunnel.Port"

  @Volatile
  private var listener: TunnelListener? = null

  fun start() {
    if (GameServer.coop) {
      TcpTunnel.log { "off: this server runs a hosted (co-op) game; only dedicated servers open a tunnel" }
      return
    }
    val option = SandboxOptions.instance.getOptionByName(OPTION)
      ?: throw IllegalStateException("the sandbox option $OPTION is not registered; was sandbox-options.txt loaded?")
    val port = (option.asConfigOption() as? IntegerConfigOption)?.value
      ?: throw IllegalStateException("the sandbox option $OPTION is not an integer option")
    if (port == 0) {
      TcpTunnel.log { "off: no TCP port set (sandbox option $OPTION is 0)" }
      return
    }
    val opened = TunnelListener(port, GameServer.defaultPort, GameServer.udpPort, this)
    try {
      opened.start()
    } catch (e: IOException) {
      throw IOException("could not listen on TCP port $port", e)
    }
    listener = opened
    TcpTunnel.log {
      "listening on TCP port $port, relaying to UDP ports ${GameServer.defaultPort} and ${GameServer.udpPort}"
    }
  }

  override fun info(message: () -> String) = TcpTunnel.log(message)

  override fun error(what: String, t: Throwable) {
    TcpTunnel.disable(what, t)
    listener?.close()
  }
}
