package com.dont39.pz.syncguard;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import se.krka.kahlua.integration.annotations.LuaMethod;
import se.krka.kahlua.integration.expose.LuaJavaClassExposer;
import zombie.Lua.LuaManager;
import zombie.debug.DebugType;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * Global Lua functions for the mod's client Lua; the game's loader exposes them only from exactly javaPkgName. No hook
 * glue runs around them, so they gate and report like it.
 */
public final class LuaGlobals {
    private static final String CONTAINER_SHOWN = "Dont39SyncGuard_containerShown";

    private LuaGlobals() {
    }

    /**
     * On a join that enables the mod through the server's mod list, the game builds the Lua environment before the
     * loader loads this jar, so the loader's exposure missed it; later environments get it from the loader.
     */
    public static void exposeIfMissing() {
        if (LuaManager.env == null || LuaManager.env.rawget(CONTAINER_SHOWN) != null) {
            return;
        }
        ((LuaJavaClassExposer) LuaManager.exposer).exposeGlobalFunctions(new LuaGlobals());
        if (LuaManager.env.rawget(CONTAINER_SHOWN) == null) {
            throw new IllegalStateException("the game's Lua exposer did not add " + CONTAINER_SHOWN);
        }
        DebugType.Mod.println(PREFIX + "Lua functions added to the Lua environment the game built before this mod"
                + " loaded");
    }

    /** The container the loot window shows now (R7); anything else is ignored. */
    @LuaMethod(name = CONTAINER_SHOWN, global = true)
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
