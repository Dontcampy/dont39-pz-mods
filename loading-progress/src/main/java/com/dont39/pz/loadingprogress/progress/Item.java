package com.dont39.pz.loadingprogress.progress;

/** What a step works on, one line of the log each. */
public enum Item {
    NONE,
    JAVA_MOD,
    MOD,
    SCRIPT,
    LUA,
    TEXTURE_PACK,
    EVENT,
    /** The world-loading screen's text. */
    WORLD,
    /** A Lua event the world's loading thread fires. */
    WORLD_EVENT,
    CLOTHING_ITEM,
    /** A map cell's lot header. */
    MAP_CELL,
    TILE_DEFINITIONS,
    CHUNK,
    ANIMATION,
    /** The world load's wait for the game's file tasks: one line when it begins. */
    ASSET_WAIT
}
