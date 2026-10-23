package com.dont39.pz.syncguard.entrypoint;

import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/** Reports that the mod loaded with the mod list, and on which side. */
public final class MainEntry implements MainEntrypoint {
    @Override
    public void onMain() {
        DebugType.Mod.println(PREFIX + "%s loaded", MainEntry.class.getPackage().getImplementationVersion());
        DebugType.Mod.println(PREFIX + "ready on %s", side());
    }

    private static String side() {
        if (!GameServer.server) {
            return "a client";
        }
        return GameServer.coop ? "a co-op host" : "a dedicated server";
    }
}
