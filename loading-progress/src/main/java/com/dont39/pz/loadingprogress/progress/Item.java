package com.dont39.pz.loadingprogress.progress;

/** What the second line names while a step runs. */
public enum Item {
    NONE,
    /** Counted: n / total. */
    JAVA_MOD,
    MOD,
    SCRIPT,
    LUA,
    /** Named only. */
    TEXTURE_PACK,
    EVENT,
    /** The game's world-loading text, read when drawing. */
    WORLD
}
