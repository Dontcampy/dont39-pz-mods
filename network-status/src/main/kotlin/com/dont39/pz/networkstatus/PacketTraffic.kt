package com.dont39.pz.networkstatus

import com.dont39.pz.common.put
import com.dont39.pz.zbktutils.glue.LuaGlobal
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager
import zombie.core.network.ByteBufferReader
import zombie.core.raknet.UdpConnection
import zombie.network.GameClient
import zombie.network.PacketTypes
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

/**
 * The game packets the client received on its connection, by packet type: payload bytes and counts. Every packet,
 * chunk parts included, enters through `addIncoming` on the UdpEngine thread; the tooltip reads on the game thread.
 */
@Hooks(GameClient::class)
internal object PacketTraffic {
  private class Totals {
    val bytes = LongAdder()
    val packets = LongAdder()
  }

  private class Session(val connection: UdpConnection) {
    val started = System.currentTimeMillis()
    val types = ConcurrentHashMap<Short, Totals>()
    val reported = AtomicBoolean()
  }

  private class Row(val name: String, val bytes: Long, val packets: Long)

  // replaced only on the UdpEngine thread
  @Volatile
  private var session: Session? = null

  // the mod option, off until the Lua side says otherwise
  @Volatile
  private var reportOn = false

  /** The mod option that writes a session's table to console.txt when the session ends. */
  @LuaGlobal("Dont39AllInOne_setNetworkTrafficReport")
  fun setReport(on: Boolean) {
    reportOn = on
  }

  @Inject(method = ["addIncoming"], at = [At("HEAD")])
  fun received(id: Short, packet: ByteBufferReader?, ci: CallbackInfo) {
    // the game drops what arrives without a connection
    val connection = GameClient.connection ?: return
    checkNotNull(packet) { "GameClient.addIncoming received packet type $id without a buffer" }
    var current = session
    if (current?.connection !== connection) {
      // a kick while loading clears the connection without doDisconnect
      current?.let(::report)
      current = Session(connection)
      session = current
    }
    val totals = current.types.computeIfAbsent(id) { Totals() }
    totals.bytes.add(packet.remaining().toLong())
    totals.packets.increment()
  }

  @Inject(method = ["doDisconnect"], at = [At("HEAD")])
  fun disconnecting(reason: String?, ci: CallbackInfo) {
    session?.let(::report)
  }

  /** The counts of [connection], largest first; null before its first packet. */
  fun luaTable(connection: UdpConnection): KahluaTable? {
    val current = session?.takeIf { it.connection === connection } ?: return null
    val rows = current.rows()
    val types = LuaManager.platform.newTable()
    rows.forEachIndexed { i, row ->
      types.rawset(
        i + 1.0,
        LuaManager.platform.newTable().apply {
          rawset("name", row.name)
          put("bytes", row.bytes)
          put("packets", row.packets)
        },
      )
    }
    return LuaManager.platform.newTable().apply {
      put("seconds", (System.currentTimeMillis() - current.started) / 1000.0)
      put("bytes", rows.sumOf { it.bytes })
      rawset("types", types)
    }
  }

  private fun report(ended: Session) {
    if (!ended.reported.compareAndSet(false, true) || !reportOn) {
      return
    }
    // info, not debug: the game drops debug lines even in -debug mode unless debuglog.cfg enables them
    NetworkStatus.log {
      val rows = ended.rows()
      val total = rows.sumOf { it.bytes }
      val minutes = (System.currentTimeMillis() - ended.started) / 60_000.0
      buildString {
        append("received %.2f MB of game packets in %.1f min, by packet type:".format(Locale.ROOT, mb(total), minutes))
        rows.forEach { row ->
          val share = if (total > 0) row.bytes * 100.0 / total else 0.0
          append("\n  %-40s %10.2f MB %5.1f%% %9d packets".format(Locale.ROOT, row.name, mb(row.bytes), share, row.packets))
        }
      }
    }
  }

  private fun Session.rows(): List<Row> =
    types.map { (id, totals) -> Row(name(id), totals.bytes.sum(), totals.packets.sum()) }.sortedByDescending { it.bytes }

  private fun name(id: Short): String = PacketTypes.packetTypes[id]?.name ?: "unknown packet type $id"

  private fun mb(bytes: Long): Double = bytes / (1024.0 * 1024.0)
}
