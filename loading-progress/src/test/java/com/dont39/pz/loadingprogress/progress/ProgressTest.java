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
        assertTrue(end.largestGap().nanos() < TimeUnit.SECONDS.toNanos(7), "gap " + end.largestGap());
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
    void javaModJarsComeBeforeTheGamesModsInTheModsStep() {
        progress.fontsReady(at(0));
        progress.javaMod(1, 2, "Early", false);
        assertEquals(Item.NONE, progress.view().item(), "outside the mod list's load");
        progress.modsLoading(at(1));
        progress.javaMod(1, 2, "Zabric", false);
        progress.javaMod(1, 2, "Zabric", true);
        progress.javaMod(2, 2, "Viewpoint", false);
        Progress.View view = progress.view();
        assertEquals(Item.JAVA_MOD, view.item());
        assertEquals(2, view.index());
        assertEquals(2, view.total());
        assertEquals("Viewpoint", view.name());
        progress.javaMod(2, 2, "Viewpoint", true);
        progress.mod("A", 3);
        assertEquals(Item.MOD, progress.view().item());
        assertEquals(Step.MODS, step());
    }

    @Test
    void javaModJarsOutOfOrderThrow() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 3, "B", false), "skipped one");
        assertThrows(IllegalStateException.class, () -> progress.javaMod(1, 3, "A", true), "loaded before loading");
        progress.javaMod(1, 3, "A", false);
        assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 3, "B", false), "A not loaded yet");
        progress.javaMod(1, 3, "A", true);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 4, "B", false));
        assertEquals("launch: Java mod 2 / 4 (B) came after Java mod 1 / 3 loaded at MODS", e.getMessage());
        assertThrows(IllegalStateException.class, () -> progress.javaMod(4, 3, "D", false), "past the total");
    }

    @Test
    void aNewModListLoadCountsJavaModsAfresh() {
        progress.connect(at(0));
        progress.resetBegins(false, at(1));
        progress.modsLoading(at(2));
        progress.javaMod(1, 1, "A", false);
        progress.javaMod(1, 1, "A", true);
        progress.modsLoaded();
        progress.modsLoading(at(3));
        progress.javaMod(1, 1, "A", false);
        assertEquals(1, progress.view().index());
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
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1200), end.largestGap().nanos());
        assertEquals(Step.MODS, end.largestGap().step());
    }

    @Test
    void theLargestGapNamesTheItemItEndedAt() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.javaMod(1, 2, "A", false);
        progress.frameHandedOver(at(100));
        progress.javaMod(1, 2, "A", true);
        progress.javaMod(2, 2, "Slow", false);
        progress.frameHandedOver(at(150));
        progress.javaMod(2, 2, "Slow", true);
        progress.frameHandedOver(at(2150));
        progress.stop("why", at(2200));
        Progress.End end = ends.get(0);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2000), end.largestGap().nanos());
        assertEquals(new Progress.Shown(Item.JAVA_MOD, 2, 2, "Slow"), end.largestGap().item());
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
        assertEquals(TimeUnit.MILLISECONDS.toNanos(300), ends.get(0).largestGap().nanos());
    }

    @Test
    void theModsStepFillsItsShareWithTheJavaModJars() {
        progress.fontsReady(at(0));
        assertEquals(0.0, progress.view().fill(), "launch: step 1 of 10, no jar yet");
        progress.modsLoading(at(1));
        progress.javaMod(1, 4, "A", false);
        Progress.View view = progress.view();
        assertEquals(0.0, view.fill());
        assertEquals(0.025, view.jarEnd(), 1e-9);
        progress.javaMod(1, 4, "A", true);
        assertEquals(0.025, progress.view().fill(), 1e-9);
        assertEquals(progress.view().fill(), progress.view().jarEnd(), "no jar loads");
        for (int i = 2; i <= 4; i++) {
            progress.javaMod(i, 4, "J" + i, false);
            progress.javaMod(i, 4, "J" + i, true);
        }
        progress.mod("A", 1);
        assertEquals(0.1, progress.view().fill(), 1e-9);
        progress.reach(Boundary.TRANSLATIONS, at(2));
        assertEquals(0.2, progress.view().fill(), 1e-9);
    }

    @Test
    void aJoinsModsStepStartsWhereConnectingEnded() {
        progress.connect(at(0));
        double connecting = progress.view().fill();
        progress.resetBegins(false, at(1));
        assertEquals(connecting, progress.view().fill(), 1e-9);
        progress.modsLoading(at(2));
        progress.javaMod(1, 1, "A", false);
        assertEquals(connecting, progress.view().fill(), 1e-9);
        assertEquals(2.0 / Flow.START.steps().size(), progress.view().jarEnd(), 1e-9);
    }

    @Test
    void eachLoadingJarHasItsOwnNumberWhileTheBarShowsIt() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        assertEquals(0, progress.loadingJar());
        progress.javaMod(1, 2, "A", false);
        long a = progress.loadingJar();
        assertTrue(a != 0);
        assertEquals(a, progress.view().jar());
        progress.javaMod(1, 2, "A", true);
        assertEquals(0, progress.loadingJar(), "between jars");
        progress.javaMod(2, 2, "B", false);
        long b = progress.loadingJar();
        assertTrue(b != 0 && b != a);
        progress.stop("why", at(2));
        assertEquals(0, progress.loadingJar(), "no flow");
    }

    @Test
    void interimFramesCountForTheVisibleGapOnly() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.javaMod(1, 1, "Slow", false);
        progress.frameHandedOver(at(100));
        progress.interimFrame(at(400));
        progress.interimFrame(at(1000));
        progress.javaMod(1, 1, "Slow", true);
        progress.frameHandedOver(at(1300));
        progress.stop("why", at(1350));
        Progress.End end = ends.get(0);
        assertEquals(2, end.interimFrames());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1200), end.largestGap().nanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(600), end.largestVisibleGap().nanos());
        assertEquals(new Progress.Shown(Item.JAVA_MOD, 1, 1, "Slow"), end.largestVisibleGap().item());
        // the render thread checked the jar before the flow ended
        progress.interimFrame(at(1400));
        assertEquals(1, ends.size());
    }
}
