package com.dont39.pz.networkstatus

import com.dont39.pz.zbktutils.Feature
import zombie.network.GameServer

/**
 * In multiplayer, a signal icon in the bottom-left corner shows the connection's ping and packet loss, with the details
 * on hover. The Java side reads the game client's RakNet connection and counts the packets it receives by type.
 */
object NetworkStatus : Feature("network-status", "Dont39AllInOne") {
  override fun problem(): String? =
    if (GameServer.server) "this is a dedicated server; the icon shows a player's connection to a server" else null
}
