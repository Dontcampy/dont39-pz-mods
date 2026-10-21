package com.pzlauncher.zabric;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.mods.impl.LaunchOverrides;
import com.pzlauncher.zabric.widener.AccessWidener;

/** Called by ZombieBuddy at game start, before the game classes the lists name and the game's main class are loaded. */
public final class PreMain {
    private static volatile boolean ran;

    private PreMain() {
    }

    /** Whether ZombieBuddy preloaded the mod this launch. */
    public static boolean ran() {
        return ran;
    }

    public static void premain(String[] args) {
        ran = true;
        // first: the widener reads the lists of the mods in the requested folders
        LaunchOverrides.read();
        AccessWidener.install();
        LaunchOverrides.install();
        Lifecycle.install();
    }
}
