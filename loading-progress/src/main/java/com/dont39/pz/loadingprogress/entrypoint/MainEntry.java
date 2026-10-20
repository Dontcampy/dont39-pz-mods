package com.dont39.pz.loadingprogress.entrypoint;

import com.dont39.pz.loadingprogress.hooks.Loading;
import com.pzlauncher.zabric.entrypoint.MainEntrypoint;
import com.pzlauncher.zabric.widener.AccessWidener;
import zombie.debug.DebugType;

import static com.dont39.pz.loadingprogress.hooks.Loading.PREFIX;

/** Reports, once the mod list has loaded, whether the mod runs and whether the launch showed progress. */
public final class MainEntry implements MainEntrypoint {
    @Override
    public void onMain() {
        // a server lists the mod or only preloads it: the line comes once, from here or ServerHooks
        if (!Loading.PROGRESS.client()) {
            return;
        }
        DebugType.Mod.println(PREFIX + "%s loaded", MainEntry.class.getPackage().getImplementationVersion());
        if (AccessWidener.report(Loading.MOD_ID, MainEntry.class) != null) {
            Loading.PROGRESS.stop("the access widener did not apply the mod's list", System.nanoTime());
            DebugType.Mod.warn(PREFIX + "idle: the access widener did not apply the mod's list (line above)");
            return;
        }
        if (!Loading.PROGRESS.launchSeen()) {
            DebugType.Mod.println(PREFIX + "this launch shows progress only from the mod list on: Zabric opens the"
                    + " hooks at game start from the launch after it registered the mod");
        }
    }
}
