package com.dont39.pz.tunnel

import com.dont39.pz.tunnel.TunnelStore.Mode
import com.dont39.pz.tunnel.transport.kcp.KcpTransport
import com.dont39.pz.tunnel.transport.tcp.TcpTransport
import com.dont39.pz.zbktutils.glue.LuaGlobal
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaEventManager
import zombie.Lua.LuaManager
import zombie.core.Translator
import zombie.core.raknet.UdpEngine
import zombie.core.znet.SteamUtils

/**
 * The client's one tunnel, opened for each connect to an address whose stored tunnel is on. A tunnel that cannot be
 * opened stops the join with the reason on the join screen; it never falls back to UDP.
 */
internal object TunnelClient : TunnelLog {
  /** Where RakNet connects while a tunnel is up; the port stays the one it was given. */
  private const val LOCAL_HOST = "127.0.0.1"

  private var engine: UdpEngine? = null
  private var tunnel: OpenTunnel? = null

  // the store's entry the open tunnel was made from, for its mode and port in the status
  private var entry: TunnelStore.Entry? = null

  // set while this object calls Connect itself, so that call runs vanilla
  private val reentered = ThreadLocal.withInitial { false }

  /**
   * True when this took over [engine]'s connect to [host]:[port]: it connected through a tunnel, or it stopped the join
   * and told the player why. False lets vanilla connect.
   */
  @Synchronized
  fun connect(engine: UdpEngine, host: String?, port: Int, password: String?, useSteamRelay: Boolean): Boolean {
    if (reentered.get()) {
      return false
    }
    // nothing may throw into the glue: it would let vanilla connect over UDP, which the player did not choose
    val failed = try {
      close("the game started another connection")
      // Connect takes a Steam ID there, not an address
      if (host == null || (port == 0 && SteamUtils.isSteamModeEnabled())) {
        return false
      }
      return open(engine, host.trim(), port, password, useSteamRelay)
    } catch (e: TunnelFailed) {
      Tunnel.log { "stopped the join to ${host?.trim()}:$port: ${e.message}" }
      e
    } catch (t: Throwable) {
      Tunnel.disable("joining ${host?.trim()}:$port", t)
      null
    }
    stop(failed)
    return true
  }

  private fun open(engine: UdpEngine, address: String, port: Int, password: String?, useSteamRelay: Boolean): Boolean {
    val entry = TunnelStore.get(address, port) ?: return false
    val transport: Transport = when (entry.mode) {
      Mode.OFF -> return false
      Mode.TCP -> TcpTransport
      Mode.KCP -> KcpTransport
    }
    val opened = transport.open(address, entry.port, port, this)
    this.engine = engine
    tunnel = opened
    this.entry = entry
    Tunnel.log {
      val details = opened.details.let { if (it.isEmpty()) "" else " ($it)" }
      "connecting to $address:$port through the ${transport.name} tunnel on port ${entry.port}$details"
    }
    reentered.set(true)
    try {
      engine.Connect(LOCAL_HOST, port, password, useSteamRelay)
    } finally {
      reentered.set(false)
    }
    return true
  }

  /** Shows why the join stopped, as vanilla's unknown host: the join screen shows the text and a Back button. */
  private fun stop(failed: TunnelFailed?) {
    try {
      val text = if (failed == null) {
        close("an unexpected error")
        Translator.getText("UI_Dont39AllInOne_Tunnel_Failed_Error")
      } else {
        Translator.getText("UI_Dont39AllInOne_Tunnel_Failed_${failed.reason.key}", *failed.args.toTypedArray())
      }
      LuaEventManager.triggerEvent("OnConnectFailed", text)
    } catch (t: Throwable) {
      // the join stays stopped without the text; the player can still abort it
      Tunnel.disable("showing why a join stopped", t)
    }
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
    entry = null
    engine = null
  }

  /**
   * For the network status display: the open tunnel's mode, port and, for KCP, its cumulative counters; nil when none
   * is open or the feature is off, as a disabled feature no longer sees the tunnel close.
   */
  @LuaGlobal("Dont39AllInOne_getTunnelStatus")
  @Synchronized
  fun luaStatus(): KahluaTable? {
    if (!Tunnel.active()) return null
    return try {
      status()
    } catch (t: Throwable) {
      Tunnel.disable("reading the tunnel's status", t)
      null
    }
  }

  private fun status(): KahluaTable? {
    val tunnel = tunnel ?: return null
    val entry = checkNotNull(entry) { "a tunnel is open without its entry" }
    val table = LuaManager.platform.newTable()
    table.rawset("mode", entry.mode.id)
    table.rawset("port", entry.port.toDouble())
    tunnel.stats()?.let {
      table.rawset("segmentsSent", it.segmentsSent.toDouble())
      table.rawset("retransmitsTimeout", it.retransmitsTimeout.toDouble())
      table.rawset("retransmitsFast", it.retransmitsFast.toDouble())
      table.rawset("fecExpected", it.seqExpected.toDouble())
      table.rawset("fecMissing", it.seqMissing.toDouble())
      table.rawset("fecRecovered", it.fecRecovered.toDouble())
      table.rawset("fecUnrecoverable", it.fecUnrecoverable.toDouble())
      table.rawset("srtt", it.srttMillis.toDouble())
    }
    return table
  }

  override fun info(message: () -> String) = Tunnel.log(message)

  override fun error(what: String, t: Throwable) = Tunnel.disable(what, t)
}
