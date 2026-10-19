package com.dont39.pz.loadingprogress.progress;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

/** A load the bar covers: its steps in order, and which boundary begins each. */
public enum Flow {
    LAUNCH("launch",
            List.of(Step.MODS, Step.TRANSLATIONS, Step.UI_TEXTURES, Step.SCRIPTS, Step.CLOTHING, Step.TILES, Step.LUA,
                    Step.TEXTURES, Step.MODELS, Step.GAME_BOOT),
            Map.ofEntries(
                    entry(Boundary.MODS, Step.MODS),
                    entry(Boundary.TRANSLATIONS, Step.TRANSLATIONS),
                    entry(Boundary.SHARED_INIT, Step.UI_TEXTURES),
                    entry(Boundary.SCRIPTS, Step.SCRIPTS),
                    entry(Boundary.CLOTHING, Step.CLOTHING),
                    entry(Boundary.TILES, Step.TILES),
                    entry(Boundary.LUA, Step.LUA),
                    entry(Boundary.WINDOW_ENTER, Step.TEXTURES),
                    entry(Boundary.MODEL_MANAGER, Step.MODELS),
                    entry(Boundary.GAME_BOOT, Step.GAME_BOOT))),
    /** A server join, or a single-player load. */
    START("game start",
            List.of(Step.CONNECTING, Step.MODS, Step.TRANSLATIONS, Step.SCRIPTS, Step.MODELS, Step.CLOTHING_TILES,
                    Step.LUA, Step.LUA_EVENTS, Step.LOGIN_QUEUE, Step.PREPARING_WORLD, Step.LOADING_WORLD, Step.READY,
                    Step.ENTERING_WORLD),
            Map.ofEntries(
                    entry(Boundary.MODS, Step.MODS),
                    entry(Boundary.TRANSLATIONS, Step.TRANSLATIONS),
                    entry(Boundary.SCRIPTS, Step.SCRIPTS),
                    entry(Boundary.SPRITE_MODELS, Step.MODELS),
                    entry(Boundary.CLOTHING, Step.CLOTHING_TILES),
                    entry(Boundary.LUA, Step.LUA),
                    entry(Boundary.GAME_BOOT, Step.LUA_EVENTS),
                    entry(Boundary.LOGIN_QUEUE, Step.LOGIN_QUEUE),
                    entry(Boundary.WORLD_PREPARE, Step.PREPARING_WORLD),
                    entry(Boundary.WORLD_LOAD, Step.LOADING_WORLD),
                    entry(Boundary.WORLD_READY, Step.READY),
                    entry(Boundary.WORLD_ENTER, Step.ENTERING_WORLD))),
    /** A Lua reset for the main menu's mod list. */
    RELOAD("mod reload",
            List.of(Step.MODS, Step.TRANSLATIONS, Step.SCRIPTS, Step.MODELS, Step.CLOTHING_TILES, Step.LUA,
                    Step.LUA_EVENTS),
            Map.ofEntries(
                    entry(Boundary.MODS, Step.MODS),
                    entry(Boundary.TRANSLATIONS, Step.TRANSLATIONS),
                    entry(Boundary.SCRIPTS, Step.SCRIPTS),
                    entry(Boundary.SPRITE_MODELS, Step.MODELS),
                    entry(Boundary.CLOTHING, Step.CLOTHING_TILES),
                    entry(Boundary.LUA, Step.LUA),
                    entry(Boundary.GAME_BOOT, Step.LUA_EVENTS)));

    private final String label;
    private final List<Step> steps;
    private final Map<Boundary, Step> begins;

    Flow(String label, List<Step> steps, Map<Boundary, Step> begins) {
        this.label = label;
        this.steps = steps;
        this.begins = begins;
    }

    /** For the log. */
    public String label() {
        return label;
    }

    public List<Step> steps() {
        return steps;
    }

    /** The step {@code boundary} begins, or null when the flow does not pass it. */
    Step begins(Boundary boundary) {
        return begins.get(boundary);
    }
}
