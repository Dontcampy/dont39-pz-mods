package com.dont39.pz.serverreborn.networksyncoptimization.client

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.ServerRebornLua
import com.dont39.pz.zbktutils.glue.LuaGlobal
import se.krka.kahlua.integration.expose.LuaJavaClassExposer
import zombie.Lua.LuaManager
import zombie.inventory.ItemContainer
import zombie.network.GameServer

/** Global Lua functions for the mod's client Lua. No hook glue runs around them, so they gate and report like it. */
internal object LuaGlobals {
  private const val CONTAINER_SHOWN = "Dont39ServerReborn_containerShown"

  /**
   * On a join that enables the mod through the server's mod list, the game builds the Lua environment before the
   * loader loads this jar, so the loader's exposure missed it; later environments get it from the loader.
   */
  fun exposeIfMissing() {
    val env = LuaManager.env ?: return
    if (env.rawget(CONTAINER_SHOWN) != null) return
    (LuaManager.exposer as LuaJavaClassExposer).exposeGlobalFunctions(ServerRebornLua)
    checkNotNull(env.rawget(CONTAINER_SHOWN)) { "the game's Lua exposer did not add $CONTAINER_SHOWN" }
    ServerReborn.log { "Lua functions added to the Lua environment the game built before this mod loaded" }
  }

  /** The container the loot window shows now (R7); anything else is ignored. */
  @LuaGlobal(CONTAINER_SHOWN)
  fun containerShown(container: Any?) {
    if (!ServerReborn.active() || GameServer.server || container !is ItemContainer) return
    try {
      ClientSync.containerShown(container)
    } catch (t: Throwable) {
      ServerReborn.disable("checking a container the loot window shows", t)
    }
  }
}
