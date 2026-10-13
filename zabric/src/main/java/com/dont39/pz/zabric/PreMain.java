package com.dont39.pz.zabric;

import com.dont39.pz.zabric.widener.AccessWidener;

/** Called by ZombieBuddy at game start, before the game classes the lists name are loaded. */
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
        AccessWidener.install();
    }
}
