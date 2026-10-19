package com.dont39.pz.loadingprogress.progress;

/** A point in the game's loading code where a step can begin; each flow maps the ones it passes to its steps. */
public enum Boundary {
    /** The mod list loads. */
    MODS,
    TRANSLATIONS,
    /** {@code GameWindow.initShared}. */
    SHARED_INIT,
    SCRIPTS,
    /** {@code SpriteModelManager.init}, the first model step of a Lua reset. */
    SPRITE_MODELS,
    CLOTHING,
    TILES,
    /** {@code LuaManager.LoadDirBase()}: shared and client Lua. */
    LUA,
    /** {@code GameWindow.enter}. */
    WINDOW_ENTER,
    /** {@code ModelManager.create}. */
    MODEL_MANAGER,
    /** The {@code OnGameBoot} event. */
    GAME_BOOT,
    LOGIN_QUEUE,
    /** {@code GameLoadingState.enter}. */
    WORLD_PREPARE,
    /** The world-loading thread starts. */
    WORLD_LOAD,
    /** The world is loaded; the game waits for a click. */
    WORLD_READY,
    /** {@code IngameState.enter}. */
    WORLD_ENTER
}
