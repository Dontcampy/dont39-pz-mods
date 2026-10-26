package com.dont39.pz.serverreborn

import com.dont39.pz.serverreborn.networksyncoptimization.client.ClientSync
import com.dont39.pz.serverreborn.networksyncoptimization.client.LuaGlobals
import com.dont39.pz.serverreborn.networksyncoptimization.game.Containers
import com.dont39.pz.serverreborn.networksyncoptimization.game.PacketScope
import com.dont39.pz.serverreborn.networksyncoptimization.game.Settings
import com.dont39.pz.serverreborn.networksyncoptimization.server.ServerSync
import com.pzlauncher.zabric.entrypoint.MainEntrypoint
import com.pzlauncher.zabric.widener.AccessWidener
import zombie.network.GameServer

/** Decides, once the mod list has loaded, whether the mod's hooks open, and reports it. */
// not named Main: ZombieBuddy loads and initializes a class of that name in javaPkgName as its own entry point
object ServerRebornMain : MainEntrypoint {
  override fun onMain() {
    ServerReborn.log { "${ServerRebornMain::class.java.`package`.implementationVersion} loaded" }
    Settings.load()
    if (AccessWidener.report(ServerReborn.MOD_ID, ServerRebornMain::class.java) != null) {
      ServerReborn.warn { "idle: the access widener did not apply the mod's list (line above)" }
      return
    }
    ServerRebornGate.targetProblem()?.let { problem ->
      ServerReborn.error { "off: $problem" }
      return
    }
    try {
      PacketScope.resolve()
      Containers.check()
    } catch (e: IllegalStateException) {
      ServerReborn.error("off: the game's packets differ from the ones this version of the mod checks", e)
      return
    }
    when {
      !GameServer.server -> {
        try {
          LuaGlobals.exposeIfMissing()
        } catch (e: RuntimeException) {
          ServerReborn.error("off: its Lua functions could not be added", e)
          return
        }
        ServerRebornGate.open(ClientSync::stop)
        ServerReborn.log { "ready on a client; it turns on for a server that runs it too" }
        Settings.reportClientSwitches()
      }
      GameServer.coop -> ServerReborn.log { "off on a co-op host: it runs on dedicated servers only" }
      else -> {
        ServerSync.start()
        ServerRebornGate.open(ServerSync::stop)
        ServerReborn.log { "active on a dedicated server: joining clients that run it are checked" }
      }
    }
  }
}
