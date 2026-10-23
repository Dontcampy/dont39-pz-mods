package com.dont39.pz.syncguard;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import se.krka.kahlua.integration.annotations.LuaMethod;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;

/**
 * Global Lua functions for the mod's client Lua; the game's loader exposes them only from exactly javaPkgName. No hook
 * glue runs around them, so they gate and report like it.
 */
public final class LuaGlobals {
    private LuaGlobals() {
    }

    /** The container the loot window shows now (R7); anything else is ignored. */
    @LuaMethod(name = "Dont39SyncGuard_containerShown", global = true)
    public static void containerShown(Object container) {
        if (!Gate.INSTANCE.active() || GameServer.server || !(container instanceof ItemContainer c)) {
            return;
        }
        try {
            ClientSync.containerShown(c);
        } catch (Throwable t) {
            Gate.INSTANCE.disable("checking a container the loot window shows", t);
        }
    }
}
