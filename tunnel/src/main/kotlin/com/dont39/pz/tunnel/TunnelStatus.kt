package com.dont39.pz.tunnel

import com.dont39.pz.common.put
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager

/**
 * The table `Dont39AllInOne_getTunnelStatus` returns, read by `Dont39AllInOne_NetworkStatus.lua`; change both together.
 * Numbers are Lua doubles. Keys: `mode` ("tcp" or "kcp") and `port` of the open tunnel, and for KCP the [LinkStats]
 * counters under their property names: `segmentsSent`, `retransmitsTimeout`, `retransmitsFast`, `seqExpected`,
 * `seqMissing`, `fecRecovered`, `fecUnrecoverable`, `srttMillis`.
 */
internal object TunnelStatus {
  fun table(entry: TunnelStore.Entry, stats: LinkStats?): KahluaTable {
    val table = LuaManager.platform.newTable()
    table.rawset("mode", entry.mode.id)
    table.put("port", entry.port)
    stats?.run {
      table.put("segmentsSent", segmentsSent)
      table.put("retransmitsTimeout", retransmitsTimeout)
      table.put("retransmitsFast", retransmitsFast)
      table.put("seqExpected", seqExpected)
      table.put("seqMissing", seqMissing)
      table.put("fecRecovered", fecRecovered)
      table.put("fecUnrecoverable", fecUnrecoverable)
      table.put("srttMillis", srttMillis)
    }
    return table
  }
}
