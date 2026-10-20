package com.dont39.pz.loadingprogress.progress;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressTest {
    private final List<Progress.End> ends = new ArrayList<>();
    private boolean client = true;
    private int notClient;
    private final Progress progress = new Progress(() -> client, () -> notClient++, ends::add);
    private long now;

    private long at(long millis) {
        now = TimeUnit.MILLISECONDS.toNanos(millis);
        return now;
    }

    private Step step() {
        return progress.view().current();
    }

    private Line newest() {
        List<Line> log = progress.view().log();
        return log.get(log.size() - 1);
    }

    @Test
    void launchRunsItsStepsInOrder() {
        progress.fontsReady(at(0));
        assertEquals(Step.MODS, step());
        progress.modsLoading(at(10));
        progress.mod("A", 2);
        progress.mod("B", 2);
        assertEquals(Item.MOD, progress.view().item());
        assertEquals(new Line(Step.MODS, Item.MOD, 2, 2, "B"), newest());
        progress.modsLoaded();
        for (Boundary b : List.of(Boundary.TRANSLATIONS, Boundary.SHARED_INIT, Boundary.SCRIPTS,
                Boundary.SPRITE_MODELS, Boundary.CLOTHING, Boundary.TILES, Boundary.LUA, Boundary.WINDOW_ENTER,
                Boundary.MODEL_MANAGER)) {
            progress.reach(b, at(20));
        }
        assertEquals(Step.MODELS, step());
        progress.event("OnGameBoot", true, at(30));
        assertEquals(10, progress.view().step());
        assertEquals(new Line(Step.GAME_BOOT, Item.EVENT, 0, 0, "OnGameBoot"), newest());
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
        assertFalse(progress.client());
        assertEquals(1, notClient, "said once, by whichever comes first");
    }

    @Test
    void aClientSaysNothing() {
        assertTrue(progress.client());
        progress.fontsReady(at(0));
        assertEquals(0, notClient);
    }

    @Test
    void joinWaitsInTheMenuBetweenResetAndWorld() {
        progress.connect(at(0));
        assertEquals(Step.CONNECTING, step());
        assertFalse(progress.ownFrameDue(at(1000)), "the menu draws while connecting");
        progress.resetBegins(false, at(1000));
        assertEquals(Step.MODS, step());
        progress.event("OnGameBoot", true, at(1500));
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
        progress.event("OnConnectFailed", true, at(2));
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
        progress.javaMod(1, 2, "Early", false, now);
        assertEquals(Item.NONE, progress.view().item(), "outside the mod list's load");
        progress.modsLoading(at(1));
        progress.javaMod(1, 2, "Zabric", false, now);
        progress.javaMod(1, 2, "Zabric", true, now);
        progress.javaMod(2, 2, "Viewpoint", false, now);
        assertEquals(Item.JAVA_MOD, progress.view().item());
        assertEquals(new Line(Step.MODS, Item.JAVA_MOD, 2, 2, "Viewpoint"), newest());
        progress.javaMod(2, 2, "Viewpoint", true, now);
        progress.mod("A", 3);
        assertEquals(Item.MOD, progress.view().item());
        assertEquals(Step.MODS, step());
    }

    @Test
    void javaModJarsOutOfOrderThrow() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 3, "B", false, now), "skipped one");
        assertThrows(IllegalStateException.class, () -> progress.javaMod(1, 3, "A", true, now), "loaded before loading");
        progress.javaMod(1, 3, "A", false, now);
        assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 3, "B", false, now), "A not loaded yet");
        progress.javaMod(1, 3, "A", true, now);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> progress.javaMod(2, 4, "B", false, now));
        assertEquals("launch: Java mod 2 / 4 (B) came after Java mod 1 / 3 loaded at MODS", e.getMessage());
        assertThrows(IllegalStateException.class, () -> progress.javaMod(4, 3, "D", false, now), "past the total");
    }

    @Test
    void aNewModListLoadCountsJavaModsAfresh() {
        progress.connect(at(0));
        progress.resetBegins(false, at(1));
        progress.modsLoading(at(2));
        progress.javaMod(1, 1, "A", false, now);
        progress.javaMod(1, 1, "A", true, now);
        progress.modsLoaded();
        progress.modsLoading(at(3));
        progress.javaMod(1, 1, "A", false, now);
        assertTrue(progress.loadingJar() != 0, "jar 1 loads again");
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
        assertEquals(new Line(Step.LUA, Item.LUA, 2, 2, "two.lua"), newest());
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
        assertEquals(new Line(Step.SCRIPTS, Item.SCRIPT, 2, 2, "recipes.txt"), newest());
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
        assertEquals(new Line(Step.UI_TEXTURES, Item.TEXTURE_PACK, 0, 0, "UI"), newest());
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
        progress.event("OnGameBoot", true, at(1300));
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
        progress.javaMod(1, 2, "A", false, now);
        progress.frameHandedOver(at(100));
        progress.javaMod(1, 2, "A", true, now);
        progress.javaMod(2, 2, "Slow", false, now);
        progress.frameHandedOver(at(150));
        progress.javaMod(2, 2, "Slow", true, now);
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
    void theBarFillsByStep() {
        progress.fontsReady(at(0));
        assertEquals(0.1, progress.view().fill(), 1e-9, "launch: step 1 of 10");
        progress.modsLoading(at(1));
        progress.javaMod(1, 4, "A", false, now);
        assertEquals(0.1, progress.view().fill(), 1e-9, "jars do not move the bar");
        progress.reach(Boundary.TRANSLATIONS, at(2));
        assertEquals(0.2, progress.view().fill(), 1e-9);
    }

    private List<String> log() {
        return progress.view().log().stream()
                .map(line -> line.isStep() ? line.index() + " " + line.step()
                        : line.item() + " " + line.index() + " " + line.name())
                .toList();
    }

    @Test
    void theLogHasALinePerStepAndPerItemNewestLast() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        progress.javaMod(1, 2, "Zabric", false, now);
        progress.javaMod(1, 2, "Zabric", true, now);
        progress.javaMod(2, 2, "Viewpoint", false, now);
        progress.javaMod(2, 2, "Viewpoint", true, now);
        progress.mod("A", 1);
        progress.reach(Boundary.TRANSLATIONS, at(2));
        progress.reach(Boundary.SHARED_INIT, at(3));
        progress.texturePack("UI");
        progress.texturePack("UI");
        progress.texturePack("UI2");
        assertEquals(List.of("1 MODS", "JAVA_MOD 1 Zabric", "JAVA_MOD 2 Viewpoint", "MOD 1 A", "2 TRANSLATIONS",
                "3 UI_TEXTURES", "TEXTURE_PACK 0 UI", "TEXTURE_PACK 0 UI2"), log());
        Line step = progress.view().log().get(4);
        assertEquals(10, step.total(), "of the flow's steps");
    }

    @Test
    void theLogKeepsItsNewestLines() {
        progress.fontsReady(at(0));
        progress.scriptsLoading(null, at(1));
        int files = Progress.LOG_LINES * 3;
        for (int i = 1; i <= files; i++) {
            progress.script("s/" + i, i + ".txt", files);
        }
        List<Line> log = progress.view().log();
        assertEquals(Progress.LOG_LINES, log.size());
        assertEquals(files - Progress.LOG_LINES + 1, log.get(0).index());
        assertEquals(files, log.get(log.size() - 1).index());
    }

    @Test
    void aStuckItemStaysInTheLog() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        progress.javaMod(1, 1, "Slow", false, now);
        for (int i = 0; i < 100; i++) {
            progress.frameHandedOver(at(1000L * i));
        }
        assertEquals("JAVA_MOD 1 Slow", log().get(log().size() - 1));
    }

    @Test
    void eachFlowStartsItsOwnLog() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        progress.mod("A", 1);
        progress.reach(Boundary.GAME_BOOT, at(2));
        progress.launchDone(at(3));
        progress.connect(at(4));
        assertEquals(List.of("1 CONNECTING"), log());
        progress.event("OnConnectFailed", true, at(5));
        progress.worldPrepares(at(6));
        assertEquals(List.of(Line.step(Step.PREPARING_WORLD, 10, 13)), progress.view().log());
    }

    @Test
    void aJoinsLogGoesOnAfterTheMenu() {
        progress.connect(at(0));
        progress.resetBegins(false, at(1));
        progress.resetEnds(false, at(2));
        progress.worldPrepares(at(3));
        assertEquals(List.of("1 CONNECTING", "2 MODS", "10 PREPARING_WORLD"), log());
    }

    @Test
    void theWorldLoadingTextIsAnItemPerChange() {
        progress.worldText("Loading");
        assertNull(progress.view(), "no flow");
        progress.worldPrepares(at(0));
        progress.worldText("too early");
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.worldText("Loading map");
        progress.worldText("Loading map");
        progress.worldText(" ");
        progress.worldText("Loading chunks");
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD", "WORLD 0 Loading map", "WORLD 0 Loading chunks"),
                log());
    }

    @Test
    void theWorldLoadLogsTheItemsOfItsThreads() {
        progress.worldPrepares(at(0));
        progress.clothingItems(1, 2);
        progress.mapCell("0_0", 1);
        assertFalse(progress.loadsWorld(), "preparing, not loading");
        progress.reach(Boundary.WORLD_LOAD, at(1));
        assertTrue(progress.loadsWorld());
        progress.clothingItems(0, 3);
        progress.clothingItems(2, 3);
        progress.clothingItems(2, 3);
        progress.worldText("Loading tile definitions");
        progress.event("OnInitWorld", false, at(2));
        progress.event("OnTick", true, at(2));
        progress.mapCell("26_40", 2);
        progress.tileDefinitions("E:/pz/media/newtiledefinitions.tiles");
        progress.mapCell("27_40", 2);
        progress.modTileDefinitions();
        progress.worldText("Loading tile definitions");
        progress.chunkQueued(5);
        progress.chunkQueued(5);
        progress.chunks(5);
        progress.chunks(6);
        progress.chunks(7);
        progress.chunks(7);
        progress.animations(10, 20);
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD", "CLOTHING_ITEM 0 null", "CLOTHING_ITEM 2 null",
                "WORLD 0 Loading tile definitions", "WORLD_EVENT 0 OnInitWorld", "MAP_CELL 1 26_40",
                "TILE_DEFINITIONS 0 newtiledefinitions.tiles", "MAP_CELL 2 27_40", "TILE_DEFINITIONS 0 null",
                "CHUNK 0 null", "CHUNK 1 null",
                "CHUNK 2 null", "ANIMATION 10 null"), log());
        progress.reach(Boundary.WORLD_READY, at(3));
        assertFalse(progress.loadsWorld());
    }

    @Test
    void worldEventsAndTileDefinitionsShowOncePerStep() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.tileDefinitions("E:/pz/media/tiledefinitions.tiles");
        progress.tileDefinitions("E:/pz/media/newtiledefinitions.tiles");
        progress.modTileDefinitions();
        progress.tileDefinitions("E:/pz/media/tiledefinitions.tiles");
        progress.tileDefinitions("E:/pz/media/newtiledefinitions.tiles");
        progress.modTileDefinitions();
        for (int i = 0; i < 3; i++) {
            progress.event("OnWeatherPeriodStart", false, at(2));
            progress.event("OnWeatherPeriodStop", false, at(2));
        }
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD", "TILE_DEFINITIONS 0 tiledefinitions.tiles",
                "TILE_DEFINITIONS 0 newtiledefinitions.tiles", "TILE_DEFINITIONS 0 null",
                "WORLD_EVENT 0 OnWeatherPeriodStart", "WORLD_EVENT 0 OnWeatherPeriodStop"), log());
        assertEquals(Item.WORLD_EVENT, progress.view().item(), "a repeat is still the current item");
    }

    @Test
    void theNextWorldLoadShowsItsEventsAgain() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.event("OnInitWorld", false, at(2));
        progress.reach(Boundary.WORLD_READY, at(3));
        progress.reach(Boundary.WORLD_ENTER, at(4));
        progress.inWorld(at(5));
        progress.worldLeft(at(6));
        progress.worldPrepares(at(7));
        progress.reach(Boundary.WORLD_LOAD, at(8));
        progress.event("OnInitWorld", false, at(9));
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD", "WORLD_EVENT 0 OnInitWorld"), log());
    }

    @Test
    void gameThreadEventsShowAgainAfterOthers() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.reach(Boundary.WORLD_READY, at(2));
        progress.reach(Boundary.WORLD_ENTER, at(3));
        progress.event("OnCreatePlayer", true, at(4));
        progress.event("OnGameStart", true, at(4));
        progress.event("OnCreatePlayer", true, at(4));
        assertEquals(List.of("EVENT 0 OnCreatePlayer", "EVENT 0 OnGameStart", "EVENT 0 OnCreatePlayer"),
                log().subList(log().size() - 3, log().size()));
    }

    @Test
    void worldCountsPastTheirTotalsThrow() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.mapCell("1_1", 1);
        assertThrows(IllegalStateException.class, () -> progress.mapCell("1_2", 1));
        assertThrows(IllegalStateException.class, () -> progress.animations(3, 2));
        progress.chunkQueued(4);
        assertThrows(IllegalStateException.class, () -> progress.chunks(6));
        assertThrows(IllegalStateException.class, () -> progress.chunks(3));
    }

    @Test
    void chunksWithoutQueuedOnesHaveNoCount() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.chunks(4);
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD"), log());
    }

    @Test
    void theNextWorldLoadCountsAgain() {
        progress.worldPrepares(at(0));
        progress.reach(Boundary.WORLD_LOAD, at(1));
        progress.mapCell("1_1", 1);
        progress.chunkQueued(0);
        progress.reach(Boundary.WORLD_READY, at(2));
        progress.reach(Boundary.WORLD_ENTER, at(3));
        progress.inWorld(at(4));
        progress.worldLeft(at(5));
        progress.worldPrepares(at(6));
        progress.reach(Boundary.WORLD_LOAD, at(7));
        progress.mapCell("1_1", 1);
        progress.chunks(3);
        assertEquals(List.of("10 PREPARING_WORLD", "11 LOADING_WORLD", "MAP_CELL 1 1_1"), log());
    }

    @Test
    void itemsFromManyThreadsKeepTheirOrderInTheLog() throws InterruptedException {
        progress.fontsReady(at(0));
        progress.reach(Boundary.SHARED_INIT, at(1));
        int threads = 4;
        int packs = 5_000;
        AtomicBoolean done = new AtomicBoolean();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread reader = new Thread(() -> {
            while (!done.get()) {
                try {
                    List<Line> log = progress.view().log();
                    assertTrue(log.size() <= Progress.LOG_LINES);
                    log.forEach(Objects::requireNonNull);
                } catch (Throwable e) {
                    failures.add(e);
                    return;
                }
            }
        });
        reader.start();
        List<Thread> writers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String prefix = t + ":";
            Thread writer = new Thread(() -> {
                for (int i = 0; i < packs; i++) {
                    progress.texturePack(prefix + i);
                }
            });
            writers.add(writer);
            writer.start();
        }
        for (Thread writer : writers) {
            writer.join();
        }
        done.set(true);
        reader.join();
        assertEquals(List.of(), failures);
        List<Line> log = progress.view().log();
        assertEquals(Progress.LOG_LINES, log.size());
        // each thread's packs in the order it logged them
        int[] last = new int[threads];
        Arrays.fill(last, -1);
        for (Line line : log) {
            String[] parts = line.name().split(":");
            int thread = Integer.parseInt(parts[0]);
            int pack = Integer.parseInt(parts[1]);
            assertTrue(pack > last[thread], line.name());
            last[thread] = pack;
        }
        assertTrue(Arrays.stream(last).anyMatch(pack -> pack == packs - 1), "the last pack logged is kept");
    }

    @Test
    void jarFramesComeTwoAtOnceThenOnePerInterval() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.frameHandedOver(at(10));
        assertTrue(progress.takeJarFrame(at(20)), "right after another frame");
        assertTrue(progress.takeJarFrame(at(25)), "a long jar right after a short one");
        assertFalse(progress.takeJarFrame(at(30)), "a third at once");
        assertTrue(progress.takeJarFrame(at(70)), "an interval after the first");
        // a run of jars that return at once
        int frames = 0;
        for (int t = 71; t < 300; t++) {
            if (progress.takeJarFrame(at(t))) {
                frames++;
            }
        }
        assertEquals(4, frames, "one per interval: at 120, 170, 220 and 270 ms");
        assertTrue(progress.takeJarFrame(at(1000)), "after a pause");
        assertTrue(progress.takeJarFrame(at(1001)), "two at once again");
        assertFalse(progress.takeJarFrame(at(1002)));
    }

    @Test
    void noJarFrameWhereTheGameDrawsItsOwn() {
        progress.connect(at(0));
        assertFalse(progress.takeJarFrame(at(100)), "the connect screen draws its frames");
    }

    @Test
    void theJavaModPassReportsItsLargestVisibleGap() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.frameHandedOver(at(100));
        progress.javaMod(1, 1, "Slow", false, at(1500));
        progress.frameHandedOver(at(1500));
        progress.interimFrame(at(1800));
        progress.javaMod(1, 1, "Slow", true, at(2100));
        progress.frameHandedOver(at(2100));
        progress.mod("M", 1);
        progress.frameHandedOver(at(2200));
        progress.stop("why", at(2300));
        Progress.End end = ends.get(0);
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1400), end.largestVisibleGap().nanos(), "before the first jar");
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1400), end.javaModGap().nanos(), "ended at the first jar");
    }

    @Test
    void knownCallsAreTheirShareOfAGap() {
        progress.fontsReady(at(0));
        progress.reach(Boundary.TILES, at(0));
        progress.frameHandedOver(at(100));
        progress.knownCall(Known.COMMENT_STRIPPING, at(200));
        progress.knownCallEnded(at(2200));
        progress.frameHandedOver(at(2300));
        progress.knownCall(Known.STEAM_ITEMS, at(2400));
        progress.knownCallEnded(at(2500));
        progress.frameHandedOver(at(3500));
        progress.stop("why", at(3600));
        Progress.End end = ends.get(0);
        assertNull(end.javaModGap());
        Progress.Gap largest = end.largestVisibleGap();
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2200), largest.nanos());
        assertEquals(Known.COMMENT_STRIPPING, largest.known());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(2000), largest.knownNanos());
        Progress.Gap unknown = end.unknownGap();
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1200), unknown.nanos(), "1100 ms outside known calls");
        assertEquals(Known.STEAM_ITEMS, unknown.known());
    }

    @Test
    void aKnownCallStillRunningCountsUpToTheFrame() {
        progress.fontsReady(at(0));
        progress.knownCall(Known.STEAM_ITEMS, at(100));
        progress.frameHandedOver(at(1100));
        progress.frameHandedOver(at(1600));
        progress.knownCallEnded(at(1700));
        progress.frameHandedOver(at(1800));
        progress.stop("why", at(1800));
        Progress.Gap largest = ends.get(0).largestVisibleGap();
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1000), largest.knownNanos());
        assertEquals(TimeUnit.MILLISECONDS.toNanos(100), ends.get(0).unknownGap().unknownNanos());
    }

    @Test
    void eachLoadingJarHasItsOwnNumberWhileTheBarShowsIt() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(1));
        assertEquals(0, progress.loadingJar());
        progress.javaMod(1, 2, "A", false, now);
        long a = progress.loadingJar();
        assertTrue(a != 0);
        assertEquals(a, progress.view().jar());
        progress.javaMod(1, 2, "A", true, now);
        assertEquals(0, progress.loadingJar(), "between jars");
        progress.javaMod(2, 2, "B", false, now);
        long b = progress.loadingJar();
        assertTrue(b != 0 && b != a);
        progress.stop("why", at(2));
        assertEquals(0, progress.loadingJar(), "no flow");
    }

    @Test
    void interimFramesCountForTheVisibleGapOnly() {
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.javaMod(1, 1, "Slow", false, now);
        progress.frameHandedOver(at(100));
        progress.interimFrame(at(400));
        progress.interimFrame(at(1000));
        progress.javaMod(1, 1, "Slow", true, now);
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

    @Test
    void interimSkipsAreCountedPerFlow() {
        progress.interimSkipped();
        progress.fontsReady(at(0));
        progress.modsLoading(at(0));
        progress.interimSkipped();
        progress.interimSkipped();
        progress.stop("why", at(10));
        assertEquals(2, ends.get(0).interimSkips(), "none counted before the flow");
    }
}
