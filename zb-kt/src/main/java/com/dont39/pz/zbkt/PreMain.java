package com.dont39.pz.zbkt;

/**
 * Called by ZombieBuddy at game start when it preloads this mod, after Zabric and before the preload mods that require
 * this one: installs Kotlin before any other code can load the game's old one. Logs nothing, the game's log is not up;
 * {@link Main} reports.
 */
public final class PreMain {
    private PreMain() {
    }

    public static void premain(String[] args) {
        Install.atGameStart();
    }
}
