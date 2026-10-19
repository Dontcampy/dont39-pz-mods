package com.dont39.pz.loadingprogress.progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTest {
    private final List<Progress.End> ends = new ArrayList<>();
    private boolean client = true;
    private final Progress progress = new Progress(() -> client, ends::add);
    private long now;

    private long at(long millis) {
        now = TimeUnit.MILLISECONDS.toNanos(millis);
        return now;
    }

    private Step step() {
        return progress.view().current();
    }

    @Test
    void launchRunsItsStepsInOrder() {
        progress.fontsReady(at(0));
        assertEquals(Step.MODS, step());
        progress.modsLoading(at(10));
        progress.mod("A", 2);
        progress.mod("B", 2);
        Progress.View view = progress.view();
        assertEquals(Item.MOD, view.item());
        assertEquals(2, view.index());
        assertEquals("B", view.name());
        progress.modsLoaded();
        for (Boundary b : List.of(Boundary.TRANSLATIONS, Boundary.SHARED_INIT, Boundary.SCRIPTS,
                Boundary.SPRITE_MODELS, Boundary.CLOTHING, Boundary.TILES, Boundary.LUA, Boundary.WINDOW_ENTER,
                Boundary.MODEL_MANAGER)) {
            progress.reach(b, at(20));
        }
        assertEquals(Step.MODELS, step());
        progress.event("OnGameBoot", at(30));
        assertEquals(10, progress.view().step());
        assertEquals("OnGameBoot", progress.view().name());
        progress.launchDone(at(40));
        assertNull(progress.view());
        assertEquals(Progress.Outcome.DONE, ends.get(0).outcome());
        assertEquals(Flow.LAUNCH, ends.get(0).flow());
    }

    @Test
    void launchBeginsOncePerSession() {
        progress.fontsReady(at(0));
        progress.reach(Boundary.TRANSLATIONS, at(1));
        progress.fontsReady(at(2));
        assertEquals(Step.TRANSLATIONS, step());
        assertTrue(progress.launchSeen());
    }

    @Test
    void aStepOutOfOrderThrows() {
        progress.fontsReady(at(0));
        progress.reach(Boundary.SCRIPTS, at(1));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> progress.reach(Boundary.TRANSLATIONS, at(2)));
        assertTrue(e.getMessage().contains("TRANSLATIONS began after SCRIPTS"), e.getMessage());
    }

    @Test
    void launchEndingBeforeGameBootThrows() {
        progress.fontsReady(at(0));
        assertThrows(IllegalStateException.class, () -> progress.launchDone(at(1)));
    }

    @Test
    void noFlowOnADedicatedServer() {
        client = false;
        progress.fontsReady(at(0));
        progress.connect(at(1));
        progress.worldPrepares(at(2));
        assertNull(progress.view());
        assertTrue(ends.isEmpty());
    }

    @Test
    void joinWaitsInTheMenuBetweenResetAndWorld() {
        progress.connect(at(0));
        assertEquals(Step.CONNECTING, step());
        assertFalse(progress.ownFrameDue(at(1000)), "the menu draws while connecting");
        progress.resetBegins(false, at(1000));
        assertEquals(Step.MODS, step());
        progress.event("OnGameBoot", at(1500));
        assertEquals(Step.LUA_EVENTS, step());
        progress.resetEnds(false, at(2000));
        assertNull(progress.view(), "no bar over character creation");
        // the menu's own loading does not move the flow
        progress.reach(Boundary.TRANSLATIONS, at(2100));
        progress.reach(Boundary.LOGIN_QUEUE, at(9000));
        assertEquals(Step.LOGIN_QUEUE, step());
        progress.worldPrepares(at(9100));
        progress.reach(Boundary.WORLD_LOAD, at(9200));
        progress.reach(Boundary.WORLD_READY, at(9300));
        progress.reach(Boundary.WORLD_ENTER, at(9400));
        progress.inWorld(at(9500));
        assertNull(progress.view());
        assertEquals(1, ends.size());
        Progress.End end = ends.get(0);
        assertEquals(Progress.Outcome.DONE, end.outcome());
        assertEquals(13, end.step());
        // the wait in the menu is not a gap
        assertTrue(end.largestGap() < TimeUnit.SECONDS.toNanos(7), "gap " + end.largestGap());
    }

    @Test
    void singlePlayerStartsAtTheWorldLoad() {
        progress.worldPrepares(at(0));
        assertEquals(Step.PREPARING_WORLD, step());
        assertEquals(Flow.START, progress.view().flow());
    }

    @Test
    void aFailedJoinEndsWithoutABar() {
        progress.connect(at(0));
        progress.connectionState("Connected", at(1));
        assertEquals(Step.CONNECTING, step());
        progress.event("OnConnectFailed", at(2));
        assertNull(progress.view());
        assertEquals(Progress.Outcome.STOPPED, ends.get(0).outcome());
    }

    @Test
    void aLostConnectionWhileConnectingEndsTheJoin() {
        progress.connect(at(0));
        progress.connectionState("Failed", at(1));
        assertNull(progress.view());
    }

    @Test
    void leavingTheWorldBeforeItsFirstFrameEndsTheStart() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.reach(Boundary.WORLD_READY, at(2));
        progress.reach(Boundary.WORLD_ENTER, at(3));
        progress.worldLeft(at(4));
        assertEquals(Progress.Outcome.LEFT, ends.get(0).outcome());
        // the reset to the menu's mods that follows
        progress.modsLoading(at(5));
        progress.reach(Boundary.TRANSLATIONS, at(6));
        assertNull(progress.view());
    }

    @Test
    void aStopEndsTheFlowAndKeepsNewOnesFromStarting() {
        progress.fontsReady(at(0));
        progress.stop("why", at(1));
        assertNull(progress.view());
        assertEquals(Progress.Outcome.STOPPED, ends.get(0).outcome());
        assertEquals("why", ends.get(0).why());
        progress.connect(at(2));
        progress.worldPrepares(at(3));
        assertNull(progress.view());
        assertEquals(1, ends.size());
    }

    @Test
    void backToTheMenusModsEndsAJoinAndReloads() {
        progress.connect(at(0));
        progress.resetBegins(false, at(1));
        progress.resetEnds(false, at(2));
        progress.resetBegins(true, at(3));
        assertEquals(Flow.RELOAD, progress.view().flow());
        assertEquals(Progress.Outcome.LEFT, ends.get(0).outcome());
        progress.resetEnds(false, at(4));
        assertEquals(Progress.Outcome.DONE, ends.get(1).outcome());
        assertNull(progress.view());
    }

    @Test
    void aResetInsideTheLaunchThrows() {
        progress.fontsReady(at(0));
        assertThrows(IllegalStateException.class, () -> progress.resetBegins(true, at(1)));
    }

    @Test
    void countsPastTheTotalThrow() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        progress.mod("A", 1);
        assertThrows(IllegalStateException.class, () -> progress.mod("B", 1));
    }

    @Test
    void requiredLuaFilesAreNotCounted() {
        progress.fontsReady(at(0));
        progress.reach(Boundary.LUA, at(1));
        progress.luaLoading();
        List<String> files = List.of("a/one.lua", "b/two.lua", "a/one.lua");
        progress.lua("C:\\lua\\one.lua", files);
        progress.lua("lua/required.lua", files);
        progress.luaRan();
        progress.luaRan();
        progress.lua("lua/two.lua", files);
        progress.luaRan();
        Progress.View view = progress.view();
        assertEquals(2, view.index());
        assertEquals(2, view.total());
        assertEquals("two.lua", view.name());
    }

    @Test
    void scriptFilesCountOncePerPath() {
        progress.fontsReady(at(0));
        progress.scriptsLoading("left/over.txt", at(1));
        assertTrue(progress.countsScripts());
        progress.script("left/over.txt", "over.txt", 2);
        progress.script("a/items.txt", "items.txt", 2);
        progress.script("a/items.txt", "items.txt", 2);
        progress.script("b/recipes.txt", "recipes.txt", 2);
        assertEquals(2, progress.view().index());
        assertEquals("recipes.txt", progress.view().name());
        progress.scriptsLoaded();
        assertFalse(progress.countsScripts());
    }

    @Test
    void itemsOfAnotherKindAreIgnored() {
        progress.fontsReady(at(0));
        progress.texturePack("UI");
        assertEquals(Item.NONE, progress.view().item());
        progress.reach(Boundary.SHARED_INIT, at(1));
        progress.texturePack("UI");
        assertEquals("UI", progress.view().name());
    }

    @Test
    void ownFramesAreDueAfterTheInterval() {
        progress.fontsReady(at(0));
        assertFalse(progress.ownFrameDue(at(49)));
        assertTrue(progress.ownFrameDue(at(50)));
        progress.ownFrame();
        progress.frameHandedOver(at(50));
        assertFalse(progress.ownFrameDue(at(60)));
        progress.frameHandedOver(at(1250));
        progress.reach(Boundary.TRANSLATIONS, at(1300));
        progress.event("OnGameBoot", at(1300));
        progress.launchDone(at(1400));
        Progress.End end = ends.get(0);
        assertEquals(1, end.ownFrames());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1200), end.largestGap());
        assertEquals(Step.MODS, end.largestGapStep());
    }

    @Test
    void aFrameIsSeenOnlyOnceHandedOver() {
        progress.fontsReady(at(0));
        assertFalse(progress.frameSince(at(50)));
        progress.frameHandedOver(at(60));
        assertTrue(progress.frameSince(at(50)));
        progress.stop("why", at(70));
        assertTrue(progress.frameSince(at(80)), "no flow shows");
    }

    @Test
    void aStopIsNotAFrameGap() {
        progress.fontsReady(at(0));
        progress.frameHandedOver(at(300));
        progress.stop("why", at(10_000));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300), ends.get(0).largestGap());
    }
}
