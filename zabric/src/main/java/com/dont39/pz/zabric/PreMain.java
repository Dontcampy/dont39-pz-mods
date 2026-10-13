package com.dont39.pz.zabric;

import com.dont39.pz.zabric.widener.AccessWidener;

/** Called by ZombieBuddy at game start, before the game classes the list names are loaded. */
public final class PreMain {
    /** Put there by dont39-all-in-one's build. */
    static final String LIST = "com/dont39/pz/zabric/widen.classtweaker";

    private static volatile boolean ran;

    private PreMain() {
    }

    /** Whether ZombieBuddy preloaded the mod this launch. */
    public static boolean ran() {
        return ran;
    }

    public static void premain(String[] args) {
        ran = true;
        AccessWidener.install(PreMain.class, LIST);
    }
}
