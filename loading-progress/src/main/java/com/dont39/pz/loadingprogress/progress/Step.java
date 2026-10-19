package com.dont39.pz.loadingprogress.progress;

/** A named step of a flow. */
public enum Step {
    CONNECTING(Item.NONE, false),
    MODS(Item.MOD, true),
    TRANSLATIONS(Item.NONE, true),
    UI_TEXTURES(Item.TEXTURE_PACK, true),
    SCRIPTS(Item.SCRIPT, true),
    CLOTHING(Item.NONE, true),
    TILES(Item.NONE, true),
    CLOTHING_TILES(Item.NONE, true),
    LUA(Item.LUA, true),
    TEXTURES(Item.TEXTURE_PACK, true),
    MODELS(Item.NONE, true),
    GAME_BOOT(Item.EVENT, true),
    LUA_EVENTS(Item.EVENT, true),
    LOGIN_QUEUE(Item.NONE, false),
    PREPARING_WORLD(Item.LUA, true),
    LOADING_WORLD(Item.WORLD, false),
    READY(Item.NONE, false),
    ENTERING_WORLD(Item.EVENT, true);

    private final Item item;
    private final boolean ownFrames;

    Step(Item item, boolean ownFrames) {
        this.item = item;
        this.ownFrames = ownFrames;
    }

    public Item item() {
        return item;
    }

    /** Whether the game draws no frames of its own during the step, so the mod hands over its own. */
    public boolean ownFrames() {
        return ownFrames;
    }
}
