package com.dont39.pz.fasterloading.entrypoint;

import com.dont39.pz.fasterloading.hooks.DepthOrder;
import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import static com.dont39.pz.fasterloading.Mod.PREFIX;

/**
 * Reports that the mod loaded with the mod list, and the access widener's status; whether its fixes run is decided at
 * their first use. Zabric calls it only when the mod list has the mod, not when the mod is only preloaded.
 */
public final class MainEntry implements MainEntrypoint {
    @Override
    public void onMain() {
        // a server says so once, at the first hook call, also when it only preloads the mod
        if (GameServer.server) {
            return;
        }
        DebugType.Mod.println(PREFIX + "%s loaded", MainEntry.class.getPackage().getImplementationVersion());
        DepthOrder.widened();
    }
}
