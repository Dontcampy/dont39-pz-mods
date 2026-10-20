package com.pzlauncher.zabric.mods;

/** When the loader loaded a jar in this JVM. */
public enum LoadPhase {
    /** At game start, as a preload mod. */
    PRELOAD,
    /** In the first pass over the mod list: the main menu's mods on a client, the server's on a dedicated server. */
    STARTUP,
    /** In a later pass: joining a server, leaving one. */
    LATER
}
