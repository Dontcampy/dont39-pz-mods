package com.dont39.pz.loadingprogress.text;

import com.dont39.pz.loadingprogress.progress.Item;
import com.dont39.pz.loadingprogress.progress.Known;
import com.dont39.pz.loadingprogress.progress.Line;
import com.dont39.pz.loadingprogress.progress.Progress;
import com.dont39.pz.loadingprogress.progress.Step;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StringsTest {
    private static long ms(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    @Test
    void gapNamesItsKnownCalls() {
        Progress.Gap gap = new Progress.Gap(ms(1200), Step.SCRIPTS, new Progress.Shown(Item.SCRIPT, 3, 9, "a.txt"),
                Known.COMMENT_STRIPPING, ms(800));
        assertEquals("1200 ms in Loading scripts (Script file 3 / 9: a.txt), 800 ms of it in known calls, mostly script"
                + " comment stripping", Strings.gap(gap));
    }

    @Test
    void gapLeavesOutKnownCallsUnderAMillisecond() {
        Progress.Gap gap = new Progress.Gap(ms(1200), Step.TILES, null, Known.COMMENT_STRIPPING, ms(1) - 1);
        assertEquals("1200 ms in Loading tiles and furniture", Strings.gap(gap));
    }

    @Test
    void gapWithoutKnownCalls() {
        Progress.Gap gap = new Progress.Gap(ms(40), Step.MODS, null, null, 0);
        assertEquals("40 ms in Loading mods", Strings.gap(gap));
    }

    @Test
    void logLines() {
        assertEquals("3 / 10  Loading UI textures", Strings.line(new Line(Step.UI_TEXTURES, null, 3, 10, null), false));
        assertEquals("3 / 10  加载界面纹理", Strings.line(new Line(Step.UI_TEXTURES, null, 3, 10, null), true));
        assertEquals("Java mod 2 / 14: Viewpoint",
                Strings.line(new Line(Step.MODS, Item.JAVA_MOD, 2, 14, "Viewpoint"), false));
        assertEquals("Lua 事件：OnGameBoot",
                Strings.line(new Line(Step.GAME_BOOT, Item.EVENT, 0, 0, "OnGameBoot"), true));
        assertEquals("Loading map", Strings.line(new Line(Step.LOADING_WORLD, Item.WORLD, 0, 0, "Loading map"), false));
    }

    @Test
    void worldLoadLines() {
        assertEquals("Map cell 3 / 1200: 26_40",
                Strings.line(new Line(Step.LOADING_WORLD, Item.MAP_CELL, 3, 1200, "26_40"), false));
        assertEquals("地图格 3 / 1200：26_40",
                Strings.line(new Line(Step.LOADING_WORLD, Item.MAP_CELL, 3, 1200, "26_40"), true));
        assertEquals("Tile definitions: newtiledefinitions.tiles", Strings.line(
                new Line(Step.LOADING_WORLD, Item.TILE_DEFINITIONS, 0, 0, "newtiledefinitions.tiles"), false));
        assertEquals("图块定义：模组的文件",
                Strings.line(new Line(Step.LOADING_WORLD, Item.TILE_DEFINITIONS, 0, 0, null), true));
        assertEquals("Lua event: OnInitWorld",
                Strings.line(new Line(Step.LOADING_WORLD, Item.WORLD_EVENT, 0, 0, "OnInitWorld"), false));
        assertEquals("Clothing item 40 / 90",
                Strings.line(new Line(Step.LOADING_WORLD, Item.CLOTHING_ITEM, 40, 90, null), false));
        assertEquals("区块 12 / 361", Strings.line(new Line(Step.LOADING_WORLD, Item.CHUNK, 12, 361, null), true));
        assertEquals("动画 5 / 800", Strings.line(new Line(Step.LOADING_WORLD, Item.ANIMATION, 5, 800, null), true));
        assertEquals("Waiting for assets to finish loading",
                Strings.line(new Line(Step.LOADING_WORLD, Item.ASSET_WAIT, 0, 0, null), false));
        assertNull(Strings.line(new Line(Step.LOADING_WORLD, Item.CHUNK, 0, 361, null), false));
    }
}
