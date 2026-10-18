package com.dont39.pz.tunnel

import com.dont39.pz.zbktutils.Feature
import com.dont39.pz.zbktutils.glue.LuaGlobal

/**
 * Carries the whole game connection through a tunnel for players whose network loses the game's UDP. The server relays
 * tunnels to its own RakNet ports on localhost; a client whose server entry has a tunnel chosen joins through a local
 * relay, and the join stops with the reason when the tunnel cannot be opened.
 */
object Tunnel : Feature("tunnel", "Dont39AllInOne") {
  override fun problem(): String? = null

  /** For the Lua join guard: null when the tunnel can run, else why not. */
  @LuaGlobal("Dont39AllInOne_getTunnelProblem")
  fun luaProblem(): String? = whyNotActive()
}
