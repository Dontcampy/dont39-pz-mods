package com.dont39.pz.zbskiponlinechecks.entrypoint;

import com.dont39.pz.zbskiponlinechecks.Installer;
import com.pzlauncher.zabric.entrypoint.PreLaunchEntrypoint;

/** The mod-only way; also makes Zabric keep the mod preloaded. What it throws, Zabric logs in the main phase. */
public final class PreLaunchEntry implements PreLaunchEntrypoint {
    private static volatile boolean called;

    /** Whether Zabric called it, and so reports its failure. */
    static boolean called() {
        return called;
    }

    @Override
    public void onPreLaunch() {
        called = true;
        Installer.install(Installer.Way.MOD_ONLY);
    }
}
