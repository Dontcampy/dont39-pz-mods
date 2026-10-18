package com.dont39.pz.tunnel

import com.dont39.pz.zbktutils.Feature

/**
 * Carries the whole game connection through a tunnel for players whose network loses the game's UDP. The server relays
 * tunnels to its own RakNet ports on localhost; a client with a tunnel port stored for the server's address joins
 * through a local relay, and over UDP as before when the tunnel does not answer.
 */
object Tunnel : Feature("tunnel", "Dont39AllInOne") {
  override fun problem(): String? = null
}
