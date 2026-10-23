package com.dont39.pz.fasterloading.entrypoint;

import com.dont39.pz.fasterloading.hooks.DepthOrder;
import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import com.pzlauncher.zabric.widener.AccessWidener;
import zombie.debug.DebugType;
import zombie.network.GameServer;

import static com.dont39.pz.fasterloading.Mod.MOD_ID;
import static com.dont39.pz.fasterloading.Mod.PREFIX;

/** Reports that the mod loaded with the mod list; whether its fixes run is decided at their first use. */
public final class MainEntry implements MainEntrypoint {
    @Override
    public void onMain() {
        // a server says so once, at the first hook call, also when it only preloads the mod
        if (GameServer.server) {
            return;
        }
        DebugType.Mod.println(PREFIX + "%s loaded", MainEntry.class.getPackage().getImplementationVersion());
        boolean applied = AccessWidener.report(MOD_ID, MainEntry.class) == null;
        DepthOrder.widened(applied);
        if (!applied) {
            DebugType.Mod.warn(PREFIX + "depth map order idle: the access widener did not apply the mod's list (line"
                    + " above)");
        }
    }
}
