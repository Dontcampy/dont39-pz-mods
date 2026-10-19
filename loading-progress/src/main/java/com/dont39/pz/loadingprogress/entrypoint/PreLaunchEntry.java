package com.dont39.pz.loadingprogress.entrypoint;

import com.dont39.pz.loadingprogress.hooks.JavaMods;
import com.dont39.pz.loadingprogress.hooks.Loading;
import com.pzlauncher.zabric.entrypoint.LoaderProgress;
import com.pzlauncher.zabric.entrypoint.PreLaunchEntrypoint;

/**
 * Makes Zabric keep the mod preloaded, so its hooks are open from game start, and follows ZombieBuddy's Java mod jars
 * from the launch's first pass on.
 */
public final class PreLaunchEntry implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        LoaderProgress.listen(Loading.MOD_ID, new JavaMods());
    }
}
