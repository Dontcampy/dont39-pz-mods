package com.dont39.pz.fasterloading.entrypoint;

import com.pzlauncher.zabric.entrypoint.PreLaunchEntrypoint;

/**
 * Makes Zabric keep the mod preloaded, so its hooks are open from game start and, with launch-wide hooks, on server
 * joins whose mod list lacks it.
 */
public final class PreLaunchEntry implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
    }
}
