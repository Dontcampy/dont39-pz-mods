package com.dont39.pz.tcptunnel

import com.dont39.pz.zbktutils.Feature

/**
 * Carries the whole game connection over TCP for players whose network drops UDP. The server relays tunnel connections
 * to its own RakNet port on localhost; a client with a TCP port stored for the server's address joins through a local
 * relay, and over UDP as before when the tunnel does not answer.
 */
object TcpTunnel : Feature("tcp-tunnel", "Dont39AllInOne") {
  override fun problem(): String? = null
}
