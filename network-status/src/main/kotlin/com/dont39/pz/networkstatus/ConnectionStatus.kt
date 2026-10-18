package com.dont39.pz.networkstatus

import com.dont39.pz.zbktutils.glue.LuaGlobal
import se.krka.kahlua.vm.KahluaTable
import zombie.Lua.LuaManager
import zombie.core.raknet.RakNetPeerInterface
import zombie.core.raknet.UdpConnection
import zombie.network.GameClient

/** The game client's connection to the server, read for the Lua icon about once a second. */
internal object ConnectionStatus {
  /**
   * Null when not connected. A table with `disabled = true` when the feature is not running, else the connection's
   * numbers as Lua doubles; loss values are fractions (0 to 1), as RakNet reports them.
   */
  @LuaGlobal("Dont39AllInOne_getNetworkStatus")
  fun luaStatus(): KahluaTable? {
    val connection = GameClient.connection ?: return null
    if (!NetworkStatus.active()) {
      return disabled()
    }
    return try {
      read(connection)
    } catch (t: Throwable) {
      NetworkStatus.disable("reading the connection's statistics", t)
      disabled()
    }
  }

  private fun disabled(): KahluaTable = LuaManager.platform.newTable().apply { rawset("disabled", true) }

  private fun read(connection: UdpConnection): KahluaTable? {
    val method = when (val type = GameClient.instance.udpEngine.getPeer().GetConnectionType(connection.connectedGUID)) {
      RakNetPeerInterface.ConnectionType_UDPRakNet -> "udp"
      RakNetPeerInterface.ConnectionType_Steam -> "steam"
      // the moment between RakNet dropping the connection and the game clearing it
      RakNetPeerInterface.ConnectionType_Disconnected -> return null
      else -> throw IllegalStateException("RakNet reports connection type $type, which is neither UDP nor Steam")
    }
    // on a client nothing else refreshes these
    connection.updatePing()
    val table = LuaManager.platform.newTable()
    table.rawset("method", method)
    table.put("pingLast", connection.lastPing)
    table.put("pingAvg", connection.averagePing)
    table.put("pingLow", connection.lowestPing)
    // null when RakNet has none for this connection, possibly on Steam
    val stats = connection.statistics
    table.rawset("stats", stats != null)
    if (stats != null) {
      table.put("lossLastSecond", stats.packetlossLastSecond)
      // the game's packetlossTotal stays 0; RakNet derives it from these running totals
      val resent = stats.totalUserMessageBytesResent.toDouble()
      val sent = stats.totalUserMessageBytesSent + resent
      table.put("lossTotal", if (sent > 0) resent / sent else 0.0)
      table.put("bytesSentPerSec", stats.lastActualBytesSent)
      table.put("bytesRecvPerSec", stats.lastActualBytesReceived)
      table.put("resendMessages", stats.messagesInResendBuffer)
      table.put("resendBytes", stats.bytesInResendBuffer)
      table.rawset("congestionLimited", stats.isLimitedByCongestionControl)
      table.put("congestionBps", stats.bpsLimitByCongestionControl)
      table.rawset("bandwidthLimited", stats.isLimitedByOutgoingBandwidthLimit)
      table.put("bandwidthBps", stats.bpsLimitByOutgoingBandwidthLimit)
      table.put(
        "sendBufferBytes",
        stats.bytesInSendBufferImmediate + stats.bytesInSendBufferHigh + stats.bytesInSendBufferMedium +
          stats.bytesInSendBufferLow,
      )
    }
    return table
  }

  // Lua numbers are doubles
  private fun KahluaTable.put(key: String, value: Number) = rawset(key, value.toDouble())
}
