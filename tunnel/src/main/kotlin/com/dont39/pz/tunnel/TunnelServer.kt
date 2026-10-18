package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.transport.kcp.KcpTransport
import com.dont39.pz.tunnel.transport.tcp.TcpTransport
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import zombie.SandboxOptions
import zombie.config.BooleanConfigOption
import zombie.config.ConfigOption
import zombie.config.IntegerConfigOption
import zombie.network.GameServer

/** The dedicated server's listeners, one per transport whose port sandbox option is set. */
internal object TunnelServer : TunnelLog, TunnelOptions {
  private val transports: List<Transport> = listOf(TcpTransport, KcpTransport)

  private val listeners = CopyOnWriteArrayList<OpenListener>()

  fun start() {
    if (GameServer.coop) {
      Tunnel.log { "off: this server runs a hosted (co-op) game; only dedicated servers open a tunnel" }
      return
    }
    // all ports first: a missing option disables the feature before any listener runs
    val ports = transports.map { it to int(it.portOption) }
    try {
      ports.forEach { (transport, port) -> start(transport, port) }
    } catch (t: Throwable) {
      listeners.forEach { it.close() }
      throw t
    }
  }

  private fun start(transport: Transport, port: Int) {
    if (port == 0) {
      Tunnel.log { "off: no ${transport.name} port set (sandbox option ${transport.portOption} is 0)" }
      return
    }
    val listener = try {
      transport.listen(port, GameServer.defaultPort, GameServer.udpPort, this, this)
    } catch (e: IOException) {
      // the port is the owner's choice, so the other transport keeps running
      Tunnel.log {
        "error: could not listen on ${transport.name} port $port (${TunnelIo.describe(e)}); that tunnel is off"
      }
      return
    }
    listeners += listener
    Tunnel.log {
      val details = listener.details.let { if (it.isEmpty()) "" else ", $it" }
      "listening on ${transport.name} port $port, relaying to UDP ports ${GameServer.defaultPort} and " +
        "${GameServer.udpPort}$details"
    }
  }

  override fun int(name: String): Int = option<IntegerConfigOption>(name, "an integer").value

  override fun boolean(name: String): Boolean = option<BooleanConfigOption>(name, "a boolean").value

  private inline fun <reified T : ConfigOption> option(name: String, kind: String): T {
    val option = SandboxOptions.instance.getOptionByName(name)
      ?: throw IllegalStateException("the sandbox option $name is not registered; was sandbox-options.txt loaded?")
    return option.asConfigOption() as? T ?: throw IllegalStateException("the sandbox option $name is not $kind option")
  }

  override fun info(message: () -> String) = Tunnel.log(message)

  override fun error(what: String, t: Throwable) {
    Tunnel.disable(what, t)
    listeners.forEach { it.close() }
  }
}
