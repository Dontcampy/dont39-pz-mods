package com.dont39.pz.loadingprogress.progress;

/** What the second line names while a step runs. */
public enum Item {
    NONE(false),
    JAVA_MOD(true),
    MOD(true),
    SCRIPT(true),
    LUA(true),
    TEXTURE_PACK(false),
    EVENT(false),
    /** The game's world-loading text, read when drawing. */
    WORLD(false);

    private final boolean counted;

    Item(boolean counted) {
        this.counted = counted;
    }

    /** Whether the line shows "i / total", and the sub-bar that fraction. */
    public boolean counted() {
        return counted;
    }
}
