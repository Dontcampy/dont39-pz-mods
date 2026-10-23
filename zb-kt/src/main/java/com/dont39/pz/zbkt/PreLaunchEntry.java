package com.dont39.pz.zbkt;

import com.pzlauncher.zabric.entrypoint.PreLaunchEntrypoint;

/**
 * Makes Zabric keep this mod preloaded. Runs the self-check once Kotlin is installed at game start, before the game
 * starts; {@link Main} reports the result.
 */
public final class PreLaunchEntry implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (Install.installedAtGameStart()) {
            Install.selfCheck();
        }
    }
}
