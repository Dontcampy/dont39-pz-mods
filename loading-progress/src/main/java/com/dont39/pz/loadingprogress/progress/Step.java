package com.dont39.pz.loadingprogress.progress;

import java.util.List;

/** A named step of a flow. */
public enum Step {
    CONNECTING(false),
    // ZombieBuddy's Java mod jars, then the game's mods
    MODS(true, Item.JAVA_MOD, Item.MOD),
    TRANSLATIONS(true),
    UI_TEXTURES(true, Item.TEXTURE_PACK),
    SCRIPTS(true, Item.SCRIPT),
    CLOTHING(true),
    TILES(true),
    CLOTHING_TILES(true),
    LUA(true, Item.LUA),
    TEXTURES(true, Item.TEXTURE_PACK),
    MODELS(true),
    GAME_BOOT(true, Item.EVENT),
    LUA_EVENTS(true, Item.EVENT),
    LOGIN_QUEUE(false),
    PREPARING_WORLD(true, Item.LUA),
    LOADING_WORLD(false, Item.WORLD),
    READY(false),
    ENTERING_WORLD(true, Item.EVENT);

    private final boolean ownFrames;
    private final List<Item> items;

    Step(boolean ownFrames, Item... items) {
        this.ownFrames = ownFrames;
        this.items = List.of(items);
    }

    /** Whether the step logs items of {@code kind}. */
    public boolean shows(Item kind) {
        return items.contains(kind);
    }

    /** Whether the game draws no frames of its own during the step, so the mod hands over its own. */
    public boolean ownFrames() {
        return ownFrames;
    }
}
