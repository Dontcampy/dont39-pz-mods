package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.transport.tcp.TcpTransport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import zombie.SandboxOptions
import zombie.config.IntegerConfigOption
import zombie.network.GameServer

/** The dedicated server's listeners, one per transport whose port sandbox option is set. */
internal object TunnelServer : TunnelLog {
  // the TCP option keeps its released name
  private val ports: List<Pair<Transport, String>> = listOf(TcpTransport to "Dont39AllInOne_TcpTunnel.Port")

  private val listeners = CopyOnWriteArrayList<OpenListener>()

  fun start() {
    if (GameServer.coop) {
      Tunnel.log { "off: this server runs a hosted (co-op) game; only dedicated servers open a tunnel" }
      return
    }
    ports.forEach { (transport, option) -> start(transport, intOption(option), option) }
  }

  private fun start(transport: Transport, port: Int, option: String) {
    if (port == 0) {
      Tunnel.log { "off: no ${transport.name} port set (sandbox option $option is 0)" }
      return
    }
    val listener = try {
      transport.listen(port, GameServer.defaultPort, GameServer.udpPort, this)
    } catch (e: IOException) {
      throw IOException("could not listen on ${transport.name} port $port", e)
    }
    listeners += listener
    Tunnel.log {
      "listening on ${transport.name} port $port, relaying to UDP ports ${GameServer.defaultPort} and " +
        "${GameServer.udpPort}"
    }
  }

  private fun intOption(name: String): Int {
    val option = SandboxOptions.instance.getOptionByName(name)
      ?: throw IllegalStateException("the sandbox option $name is not registered; was sandbox-options.txt loaded?")
    return (option.asConfigOption() as? IntegerConfigOption)?.value
      ?: throw IllegalStateException("the sandbox option $name is not an integer option")
  }

  override fun info(message: () -> String) = Tunnel.log(message)

  override fun error(what: String, t: Throwable) {
    Tunnel.disable(what, t)
    listeners.forEach { it.close() }
  }
}
