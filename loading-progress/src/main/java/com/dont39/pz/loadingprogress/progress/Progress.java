package com.dont39.pz.loadingprogress.progress;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The running flow, its step and item, its log and its frame gaps. Game-free: the hooks pass in what they read of the
 * game and the time. Called from the game thread and the world-loading thread; hot callers check the volatile flags
 * first. A step that begins out of order throws, which stops the mod's hooks.
 */
public final class Progress {
    /** At most this often an own frame; about 20 per second. */
    static final long FRAME_INTERVAL = TimeUnit.MILLISECONDS.toNanos(50);
    /** The most lines the log keeps, and so the most it shows. */
    public static final int LOG_LINES = 128;

    // the connection states ConnectToServer.lua treats as a failed join
    private static final Set<String> FAILED_STATES = Set.of("Failed", "Disconnected");
    // kinds whose lines come back with others in between: each tile definitions file is read twice, and the
    // climate's setup alternates two weather events
    private static final Set<Item> ONCE_PER_STEP = EnumSet.of(Item.WORLD_EVENT, Item.TILE_DEFINITIONS);

    public enum Outcome {
        DONE, STOPPED, LEFT
    }

    /**
     * What the log says when a flow ends. {@code largestGap}: between frames the game thread handed over; the others
     * between those and interim frames together: {@code largestVisibleGap} of all, {@code javaModGap} of those the
     * Java mod jars showed at (null when none showed), {@code unknownGap} the one with the most time outside
     * {@link Known} calls. {@code interimSkips}: interim frames or their copies skipped for an OpenGL error the game
     * left pending. {@code timed}: the log's lines with their times, empty unless the report is on; {@code waitedNanos}
     * of {@code nanos} went to waiting in the menu, in no line's time. {@code assetWait}: the world load's, or null.
     */
    public record End(Flow flow, Outcome outcome, String why, long nanos, int step, Step at, int ownFrames,
                      int interimFrames, int interimSkips, Gap largestGap, Gap largestVisibleGap, Gap javaModGap,
                      Gap unknownGap, List<Timed> timed, long waitedNanos, AssetWait assetWait) {
    }

    /**
     * A time without a new frame, with the step and the item shown when it ended; {@code knownNanos} of it went to
     * {@link Known} calls, most to {@code known} (null when none).
     */
    public record Gap(long nanos, Step step, Shown item, Known known, long knownNanos) {
        long unknownNanos() {
            return nanos - knownNanos;
        }
    }

    /** The item shown when a gap ended. */
    public record Shown(Item item, int index, int total, String name) {
    }

    /**
     * What a frame shows; {@code step} counts from 1. {@code fill}: the bar's filled fraction, by step. {@code jar}:
     * while a Java mod jar loads its {@link #loadingJar()}, otherwise 0. {@code log}: the flow's last lines, oldest
     * first.
     */
    public record View(Flow flow, int step, Step current, Item item, double fill, long jar, List<Line> log) {
    }

    private final BooleanSupplier client;
    private final Supplier<Settings> settingsSource;
    private final LongSupplier clock;
    private final Runnable notClient;
    private final Consumer<End> ended;

    // read at the first flow's begin, for the session
    private Settings settings;
    private volatile boolean display;
    private boolean report;

    // read without the lock on hot paths
    private volatile boolean active;
    private volatile boolean showing;
    private volatile boolean ownFrames;
    private volatile boolean loadsWorld;
    private volatile long lastFrame;
    private volatile long loadingJar;
    // a failed join heard off the game thread, for the game thread's next frame: ending a flow reads game state
    private volatile boolean joinFailedLater;

    private Flow flow;
    private int step;
    // a game start waits in the menu between its Lua reset and the world load
    private boolean paused;
    private long started;
    private Item item = Item.NONE;
    private int index;
    private int total;
    private String name;
    // the step's last line of each kind: polled items come again with other kinds in between
    private final Map<Item, Line> lastLines = new EnumMap<>(Item.class);
    // the step's lines of the kinds shown once per step
    private final Set<Line> onceLines = new HashSet<>();
    private final Log log = new Log(LOG_LINES);
    // the log's lines with their times, while the report is on; the newest still runs
    private List<Timed> timed = new ArrayList<>();
    private Line timing;
    private String timingPath;
    private long timingStarted;
    private long waitStarted;
    private long waited;
    private int ownFrameCount;
    private int interimFrameCount;
    private int interimSkipCount;
    private long lastVisible;
    // whether the last visible frame showed a Java mod jar: the gap after it is the Java mod pass's
    private boolean javaModVisible;
    private Gap largestGap;
    private Gap largestVisibleGap;
    private Gap javaModGap;
    private Gap unknownGap;
    private boolean launchSeen;
    private boolean stopped;
    private boolean notClientSeen;

    // the Known call running now, and those in the current visible gap
    private Known known;
    private long knownStarted;
    private long knownInGap;
    private Known gapKnown;
    private long gapKnownLongest;

    private boolean modsLoading;
    private int modsLoaded;
    private int javaMod;
    private int javaModTotal;
    private boolean javaModLoading;
    private long jarCount;
    // the earliest time the next jar's own frame may come, once one came
    private boolean jarFrameTaken;
    private long jarFrameNext;
    private volatile boolean scriptsLoading;
    private int scriptsLoaded;
    private String scriptPath;
    private boolean luaLoading;
    private int luaDepth;
    private int luaRun;
    private int luaTotal;
    private int mapCells;
    private int chunksQueued;
    // the game's count of loaded chunks when the first was queued
    private int chunksBefore;
    private final FileWork files = new FileWork();
    // the asset wait's line from its begin, and the wait once it ended
    private Line waitLine;
    private long waitBegan;
    private long waitBeganMillis;
    private AssetWait assetWait;

    /**
     * {@code client}: flows start only where it is true; {@code notClient} hears once where it is not.
     * {@code settings}: asked once, at the first flow's begin. {@code clock}: the time of an item's line, asked only
     * while the report is on. {@code ended} hears each flow's end. All under the lock.
     */
    public Progress(BooleanSupplier client, Supplier<Settings> settings, LongSupplier clock, Runnable notClient,
                    Consumer<End> ended) {
        this.client = client;
        this.settingsSource = settings;
        this.clock = clock;
        this.notClient = notClient;
        this.ended = ended;
    }

    /** Whether flows start here; the first time they do not, {@code notClient} hears it. */
    public synchronized boolean client() {
        if (client.getAsBoolean()) {
            return true;
        }
        if (!notClientSeen) {
            notClientSeen = true;
            notClient.run();
        }
        return false;
    }

    /** Fonts are ready for the first time this session: the launch begins. */
    public synchronized void fontsReady(long now) {
        if (launchSeen) {
            return;
        }
        launchSeen = true;
        begin(Flow.LAUNCH, Step.MODS, now);
    }

    /**
     * No flow runs for the rest of the session; a running one ends. The time since its last frame is not a gap: the
     * stop came from outside, in the middle of it.
     */
    public synchronized void stop(String why, long now) {
        stopped = true;
        if (flow != null) {
            finish(Outcome.STOPPED, why, now);
        }
    }

    /** Whether the bar and log show; when not, flows still run for the report and the frame-gap line. */
    public boolean display() {
        return display;
    }

    /** No more timing for the session: the report could not be written. */
    public synchronized void reportOff() {
        report = false;
        timing = null;
        timed = new ArrayList<>();
        update();
    }

    /** The game's file tasks, which the hooks feed while the world loads and the report is on. */
    public FileWork files() {
        return files;
    }

    /** Whether a flow runs: a cheap first check for hooks on hot paths. */
    public boolean active() {
        return active;
    }

    /** Whether the launch flow ran this session. */
    public synchronized boolean launchSeen() {
        return launchSeen;
    }

    /** The game window's start code is done: the main menu's frames follow. */
    public synchronized void launchDone(long now) {
        if (flow != Flow.LAUNCH) {
            return;
        }
        expect(Step.GAME_BOOT, "the launch ended");
        end(Outcome.DONE, null, now);
    }

    /** A server join begins. */
    public synchronized void connect(long now) {
        if (flow == Flow.START) {
            end(Outcome.LEFT, "a new join began", now);
        }
        begin(Flow.START, Step.CONNECTING, now);
    }

    /** A Lua reset begins; {@code menuMods}: for the main menu's mod list rather than a game's. */
    public synchronized void resetBegins(boolean menuMods, long now) {
        if (!menuMods && flow == Flow.START && !paused && current() == Step.CONNECTING) {
            moveTo(Step.MODS, now);
            return;
        }
        if (flow == Flow.START) {
            end(Outcome.LEFT, menuMods ? "the main menu's mod list came back" : "another Lua reset began", now);
        }
        begin(menuMods ? Flow.RELOAD : Flow.START, Step.MODS, now);
    }

    /** A Lua reset returned or threw. */
    public synchronized void resetEnds(boolean threw, long now) {
        if (flow == Flow.RELOAD) {
            end(threw ? Outcome.STOPPED : Outcome.DONE, threw ? "the Lua reset failed" : null, now);
        } else if (flow == Flow.START && !paused) {
            if (threw) {
                end(Outcome.STOPPED, "the Lua reset failed", now);
            } else if (step <= flow.steps().indexOf(Step.LUA_EVENTS)) {
                // the menu shows its own screens, e.g. character creation, until the world loads
                gaps(now);
                paused = true;
                timeUntil(now);
                waitStarted = now;
                update();
            }
        }
    }

    /** The world load begins; a single-player start without a Lua reset begins here. */
    public synchronized void worldPrepares(long now) {
        if (flow == Flow.START) {
            reachLocked(Boundary.WORLD_PREPARE, now);
        } else {
            begin(Flow.START, Step.PREPARING_WORLD, now);
        }
    }

    public void reach(Boundary boundary, long now) {
        if (!active) {
            return;
        }
        synchronized (this) {
            reachLocked(boundary, now);
        }
    }

    /** A join stopped before the world: the connect screen shows why. */
    public void joinFailed(long now) {
        if (!active) {
            return;
        }
        synchronized (this) {
            joinFailedLocked(now);
        }
    }

    /** The main menu was entered. */
    public void menu(long now) {
        left("back to the main menu", now);
    }

    /** The world was left; a start that has not drawn it yet ends before the world's reset runs. */
    public void worldLeft(long now) {
        left("the world was left before its first frame", now);
    }

    /** A frame of the world is drawn. */
    public void inWorld(long now) {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (flow != Flow.START) {
                return;
            }
            if (paused) {
                throw new IllegalStateException(flow.label() + ": the world was drawn while waiting in the menu after "
                        + current());
            }
            expect(Step.ENTERING_WORLD, "the world was drawn");
            end(Outcome.DONE, null, now);
        }
    }

    /** A Lua event fires; the world load's events are those of its loading thread, not of the game thread. */
    public void event(String event, boolean gameThread, long now) {
        if (!active) {
            return;
        }
        synchronized (this) {
            switch (event) {
                case "OnGameBoot" -> reachLocked(Boundary.GAME_BOOT, now);
                case "OnConnectFailed", "OnDisconnect" -> joinFailedLocked(now);
                default -> {
                }
            }
            item(gameThread ? Item.EVENT : Item.WORLD_EVENT, 0, 0, event);
        }
    }

    /** The network connection's state changed, on the game thread or on the network library's. */
    public void connectionState(String state, boolean gameThread, long now) {
        if (!active || state == null || !FAILED_STATES.contains(state)) {
            return;
        }
        if (!gameThread) {
            joinFailedLater = true;
            return;
        }
        synchronized (this) {
            joinFailedLocked(now);
        }
    }

    /** The mod list loads; {@link #mod} counts its mods until {@link #modsLoaded}. */
    public synchronized void modsLoading(long now) {
        modsLoading = true;
        modsLoaded = 0;
        clearJavaMods();
        reachLocked(Boundary.MODS, now);
    }

    /**
     * ZombieBuddy loads ({@code loaded} false) or has loaded Java mod jar {@code index} of {@code total}, inside the mod
     * list's load. Jars come one after another, from 1, each loaded before the next.
     */
    public synchronized void javaMod(int index, int total, String id, boolean loaded, long now) {
        if (!modsLoading || flow == null) {
            return;
        }
        String what = "Java mod " + index + " / " + total + " (" + id + ")" + (loaded ? " loaded" : "");
        boolean inOrder = loaded
                ? javaModLoading && index == javaMod && total == javaModTotal
                : !javaModLoading && index == javaMod + 1 && index <= total && (javaMod == 0 || total == javaModTotal);
        if (!inOrder) {
            throw new IllegalStateException(flow.label() + ": " + what + " came after Java mod " + javaMod + " / "
                    + javaModTotal + (javaModLoading ? "" : " loaded") + " at " + current());
        }
        javaMod = index;
        javaModTotal = total;
        javaModLoading = !loaded;
        // no interim frames without the overlay they copy
        if (item(Item.JAVA_MOD, index, total, id) && !loaded && display) {
            loadingJar = ++jarCount;
        }
        if (loaded) {
            runEnded(now);
        }
    }

    /**
     * Whether a Java mod jar that starts loading now gets an own frame, which its interim frames start from; if so, the
     * frame is taken. Two may come at once, so that a long jar right after a short one has its own; after that one per
     * frame interval, so that a run of jars that return at once gets no frame per jar.
     */
    public synchronized boolean takeJarFrame(long now) {
        if (!ownFrames || (jarFrameTaken && now - jarFrameNext < 0)) {
            return false;
        }
        jarFrameNext = jarFrameTaken ? Math.max(jarFrameNext + FRAME_INTERVAL, now) : now;
        jarFrameTaken = true;
        return true;
    }

    /** A {@link Known} call begins on the game thread. */
    public void knownCall(Known kind, long now) {
        if (!showing) {
            return;
        }
        synchronized (this) {
            if (showing && known == null) {
                known = kind;
                knownStarted = now;
            }
        }
    }

    /** The {@link Known} call returned or threw. */
    public void knownCallEnded(long now) {
        if (!showing) {
            return;
        }
        synchronized (this) {
            if (known != null) {
                countKnown(now);
                known = null;
            }
        }
    }

    /**
     * The jar ZombieBuddy loads while the bar shows it: a number that changes with each jar, or 0 when none loads or
     * the bar shows something else. For the render thread, without the lock.
     */
    public long loadingJar() {
        return loadingJar;
    }

    public synchronized void modsLoaded(long now) {
        modsLoading = false;
        runEnded(now);
    }

    /** {@code total}: the mods the list loads. */
    public void mod(String id, int total) {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (modsLoading && flow != null) {
                modsLoaded = counted(modsLoaded, total, "mod", id);
                item(Item.MOD, modsLoaded, total, id);
            }
        }
    }

    /**
     * The script files load; {@link #script} counts them until {@link #scriptsLoaded}. {@code path}: the game's
     * current script file, left from an earlier load.
     */
    public synchronized void scriptsLoading(String path, long now) {
        scriptsLoading = true;
        scriptsLoaded = 0;
        scriptPath = path;
        reachLocked(Boundary.SCRIPTS, now);
    }

    public synchronized void scriptsLoaded(long now) {
        scriptsLoading = false;
        runEnded(now);
    }

    /** Whether {@link #script} counts: the hook reads the game's current script file only then. */
    public boolean countsScripts() {
        return scriptsLoading && active;
    }

    /**
     * Called while a script file loads, maybe more than once: a new {@code path} is the next file. {@code total}: the
     * files the load reads.
     */
    public void script(String path, String file, int total) {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (scriptsLoading && flow != null && path != null && !path.equals(scriptPath)) {
                scriptPath = path;
                scriptsLoaded = counted(scriptsLoaded, total, "script file", path);
                item(Item.SCRIPT, scriptsLoaded, total, file, path);
            }
        }
    }

    /** One Lua folder (shared, client or server) loads; {@link #lua} counts its files. */
    public synchronized void luaLoading() {
        luaLoading = true;
        luaDepth = 0;
        luaRun = 0;
        luaTotal = -1;
    }

    public synchronized void luaLoaded(long now) {
        luaLoading = false;
        runEnded(now);
    }

    /**
     * A Lua file runs. {@code files}: the folder's load list, in which the game runs each file once; files they
     * require run inside them and are not counted. Pairs with {@link #luaRan()}.
     */
    public synchronized void lua(String file, Collection<String> files) {
        if (!luaLoading) {
            return;
        }
        if (luaDepth++ > 0 || flow == null) {
            return;
        }
        if (luaTotal < 0) {
            luaTotal = new HashSet<>(files).size();
        }
        luaRun = counted(luaRun, luaTotal, "Lua file", file);
        item(Item.LUA, luaRun, luaTotal, fileName(file), file);
    }

    public synchronized void luaRan() {
        if (luaLoading && luaDepth > 0) {
            luaDepth--;
        }
    }

    /** The world-loading screen's text, read each frame: a new one is the next item. */
    public void worldText(String text) {
        if (!showing || text == null || text.isBlank()) {
            return;
        }
        synchronized (this) {
            item(Item.WORLD, 0, 0, text);
        }
    }

    /** Whether the world loads: the first check of the world load's hooks, some of which run in game too. */
    public boolean loadsWorld() {
        return loadsWorld;
    }

    /** Polled while the world's loading thread waits for the clothing items: {@code loaded} of {@code total} are. */
    public void clothingItems(int loaded, int total) {
        polled(Item.CLOTHING_ITEM, loaded, total);
    }

    /**
     * Polled during the asset wait, which also waits for the animations: {@code loaded} of {@code total} are. Their line
     * lasts until all are; the rest of the wait is the wait's again.
     */
    public void animations(int loaded, int total) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            // the game polls them only during its wait; a poll from anyone else is not the wait's
            if (!loadsWorld || waitLine == null || assetWait != null) {
                return;
            }
            countedItem(Item.ANIMATION, loaded, total);
            if (loaded == total && timing != null && timing.item() == Item.ANIMATION) {
                time(waitLine, null, clock.getAsLong());
            }
        }
    }

    /**
     * The world's loading thread begins to wait for the game's file tasks; {@code millis}: the wall clock, as the game's
     * log line has it.
     */
    public void assetWaitBegins(long now, long millis) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (!loadsWorld) {
                return;
            }
            if (waitLine != null) {
                throw new IllegalStateException(flow.label() + ": the asset wait began again at " + current());
            }
            waitLine = new Line(current(), Item.ASSET_WAIT, 0, 0, null);
            waitBegan = now;
            waitBeganMillis = millis;
            files.waitBegins(now);
            item(Item.ASSET_WAIT, 0, 0, null);
        }
    }

    /** The world's loading thread goes on after its asset wait. */
    public void assetWaitEnds(long now, long millis) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (!loadsWorld) {
                return;
            }
            if (waitLine == null || assetWait != null) {
                throw new IllegalStateException(flow.label() + ": the asset wait ended at " + current()
                        + (waitLine == null ? " without beginning" : " twice"));
            }
            assetWait = files.waitEnds(waitBegan - started, now - waitBegan, waitBeganMillis, millis, true);
            runEnded(now);
        }
    }

    /** A loader thread reads the lot header of map cell {@code cell}, one of {@code total}; several run at once. */
    public void mapCell(String cell, int total) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (loadsWorld) {
                mapCells = counted(mapCells, total, "map cell", cell);
                item(Item.MAP_CELL, mapCells, total, cell);
            }
        }
    }

    /** A tile definitions file is read; each is read twice, its properties first. */
    public void tileDefinitions(String path) {
        tileDefinitionsItem(fileName(path));
    }

    /** The mods' tile definitions files are read, as one item without a name; also twice. */
    public void modTileDefinitions() {
        tileDefinitionsItem(null);
    }

    /** The world load queued a chunk; {@code loaded}: the game's count of loaded chunks. */
    public void chunkQueued(int loaded) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (loadsWorld && chunksQueued++ == 0) {
                chunksBefore = loaded;
            }
        }
    }

    /**
     * Polled while the chunks load: {@code loaded} is the game's count of loaded chunks. A join requests its chunks
     * from the server without queueing them here, so it has no count.
     */
    public void chunks(int loaded) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (loadsWorld && chunksQueued > 0) {
                countedItem(Item.CHUNK, loaded - chunksBefore, chunksQueued);
            }
        }
    }

    public void texturePack(String pack) {
        if (!active) {
            return;
        }
        synchronized (this) {
            item(Item.TEXTURE_PACK, 0, 0, pack);
        }
    }

    /** Whether the step has no frames of the game's: a cheap first check before {@link #ownFrameDue}. */
    public boolean ownFrames() {
        return ownFrames;
    }

    /** Whether to hand over an own frame now: the step has no frames of the game's, and the last frame is old. */
    public boolean ownFrameDue(long now) {
        return ownFrames && now - lastFrame >= FRAME_INTERVAL;
    }

    public synchronized void ownFrame() {
        if (flow != null) {
            ownFrameCount++;
        }
    }

    /** Whether a frame was handed over at or after {@code since}, while frames are counted. */
    public boolean frameSince(long since) {
        return !showing || lastFrame - since >= 0;
    }

    /** A frame was handed to the render thread; on the game thread. Whether a flow still shows. */
    public boolean frameHandedOver(long now) {
        if (!showing) {
            return false;
        }
        synchronized (this) {
            if (joinFailedLater) {
                joinFailedLater = false;
                joinFailedLocked(now);
            }
            if (showing) {
                gaps(now);
                lastFrame = now;
                visible(now);
            }
            return showing;
        }
    }

    /**
     * The render thread showed an interim frame. It checked {@link #loadingJar()} before, and the game thread may have
     * moved on since: the frame still counts.
     */
    public synchronized void interimFrame(long now) {
        if (!showing) {
            return;
        }
        interimFrameCount++;
        visibleGap(now);
        visible(now);
    }

    /** The render thread skipped an interim frame or its copy for an OpenGL error the game left pending. */
    public synchronized void interimSkipped() {
        if (showing) {
            interimSkipCount++;
        }
    }

    /** What to draw, or null when no flow shows. */
    public View view() {
        if (!showing) {
            return null;
        }
        synchronized (this) {
            if (!showing) {
                return null;
            }
            return new View(flow, step + 1, current(), item, (step + 1.0) / flow.steps().size(), loadingJar,
                    log.lines());
        }
    }

    private void left(String why, long now) {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (flow == Flow.START) {
                end(Outcome.LEFT, why, now);
            }
        }
    }

    private void begin(Flow next, Step first, long now) {
        if (flow != null) {
            throw new IllegalStateException(next.label() + " began while the " + flow.label() + " was at " + current());
        }
        // one heard before this flow is not its own
        joinFailedLater = false;
        if (stopped || !client()) {
            return;
        }
        if (settings == null) {
            settings = settingsSource.get();
            display = settings.display();
            report = settings.report();
        }
        if (!display && !report) {
            return;
        }
        flow = next;
        step = next.steps().indexOf(first);
        paused = false;
        started = now;
        lastFrame = now;
        lastVisible = now;
        clearItem();
        clearJavaMods();
        log.clear();
        timed = new ArrayList<>();
        timing = null;
        waited = 0;
        waitLine = null;
        assetWait = null;
        stepLine(now);
        ownFrameCount = 0;
        interimFrameCount = 0;
        interimSkipCount = 0;
        javaModVisible = false;
        known = null;
        clearKnownGap();
        largestGap = new Gap(0, first, null, null, 0);
        largestVisibleGap = largestGap;
        javaModGap = null;
        unknownGap = largestGap;
        update();
    }

    private void reachLocked(Boundary boundary, long now) {
        if (flow == null) {
            return;
        }
        Step next = flow.begins(boundary);
        if (next != null) {
            moveTo(next, now);
        }
    }

    private void moveTo(Step next, long now) {
        int i = flow.steps().indexOf(next);
        if (paused) {
            // the menu's own loading while it waits is not the flow's
            if (i <= step) {
                return;
            }
            paused = false;
            waited += now - waitStarted;
            lastFrame = now;
            lastVisible = now;
            clearKnownGap();
        } else if (i < step) {
            throw new IllegalStateException(flow.label() + ": " + next + " began after " + current());
        }
        if (i > step && waitLine != null && assetWait == null) {
            throw new IllegalStateException(flow.label() + ": " + next + " began while the asset wait of "
                    + current() + " still ran");
        }
        // the game logs the wait's begin before every finished world load; without it, its log line has changed
        if (i > step && current() == Step.LOADING_WORLD && waitLine == null) {
            throw new IllegalStateException(flow.label() + ": " + next + " began without the asset wait's line ("
                    + AssetWait.BEGINS + ") in " + current());
        }
        if (i > step) {
            step = i;
            clearItem();
            stepLine(now);
        }
        update();
    }

    private void joinFailedLocked(long now) {
        if (flow == Flow.START && !paused && current() == Step.CONNECTING) {
            end(Outcome.STOPPED, "the join stopped", now);
        }
    }

    private void expect(Step expected, String what) {
        if (current() != expected) {
            throw new IllegalStateException(flow.label() + ": " + what + " at " + current() + ", not at " + expected);
        }
    }

    // the message only on a throw: files come by the thousand
    private int counted(int done, int total, String what, String itemName) {
        if (done >= total) {
            throw new IllegalStateException(flow.label() + ": " + what + " " + itemName + " is past the " + total
                    + " counted at " + current());
        }
        return done + 1;
    }

    // whether the item shows; a new one is a line of the log, a repeat of its kind's last (a jar that loaded, an
    // event again, the same count polled) is not, nor one shown before in the step for the kinds shown once
    private boolean item(Item kind, int i, int n, String itemName) {
        return item(kind, i, n, itemName, null);
    }

    // path: a file's full path, for the report
    private boolean item(Item kind, int i, int n, String itemName, String path) {
        if (flow == null || paused || !current().shows(kind)) {
            return false;
        }
        Line line = new Line(current(), kind, i, n, itemName);
        if (ONCE_PER_STEP.contains(kind) ? onceLines.add(line) : !line.equals(lastLines.put(kind, line))) {
            log.add(line);
            if (report) {
                time(line, path, clock.getAsLong());
            }
        }
        item = kind;
        index = i;
        total = n;
        name = itemName;
        loadingJar = 0;
        return true;
    }

    private void clearItem() {
        item = Item.NONE;
        index = 0;
        total = 0;
        name = null;
        loadingJar = 0;
        lastLines.clear();
        onceLines.clear();
        mapCells = 0;
        chunksQueued = 0;
        chunksBefore = 0;
    }

    private void tileDefinitionsItem(String file) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (loadsWorld) {
                item(Item.TILE_DEFINITIONS, 0, 0, file);
            }
        }
    }

    private void polled(Item kind, int done, int total) {
        if (!loadsWorld) {
            return;
        }
        synchronized (this) {
            if (loadsWorld) {
                countedItem(kind, done, total);
            }
        }
    }

    private void countedItem(Item kind, int done, int total) {
        if (done < 0 || done > total) {
            throw new IllegalStateException(flow.label() + ": " + kind + " " + done + " / " + total + " at " + current());
        }
        item(kind, done, total, null);
    }

    private void clearJavaMods() {
        javaMod = 0;
        javaModTotal = 0;
        javaModLoading = false;
    }

    private void gaps(long now) {
        long gap = now - lastFrame;
        if (gap > largestGap.nanos()) {
            largestGap = gap(gap, now);
        }
        visibleGap(now);
    }

    private void visibleGap(long now) {
        Gap gap = gap(now - lastVisible, now);
        if (gap.nanos() > largestVisibleGap.nanos()) {
            largestVisibleGap = gap;
        }
        if ((javaModVisible || item == Item.JAVA_MOD) && (javaModGap == null || gap.nanos() > javaModGap.nanos())) {
            javaModGap = gap;
        }
        if (gap.unknownNanos() > unknownGap.unknownNanos()) {
            unknownGap = gap;
        }
    }

    // a frame was shown at now: the next gap starts
    private void visible(long now) {
        lastVisible = Math.max(lastVisible, now);
        javaModVisible = item == Item.JAVA_MOD;
        clearKnownGap();
        if (known != null) {
            knownStarted = now;
        }
    }

    private void countKnown(long now) {
        long nanos = now - knownStarted;
        knownInGap += nanos;
        if (nanos > gapKnownLongest) {
            gapKnownLongest = nanos;
            gapKnown = known;
        }
    }

    private void clearKnownGap() {
        knownInGap = 0;
        gapKnown = null;
        gapKnownLongest = 0;
    }

    private Gap gap(long nanos, long now) {
        long running = known == null ? 0 : now - knownStarted;
        Known most = running > gapKnownLongest ? known : gapKnown;
        return new Gap(nanos, current(), item == Item.NONE ? null : new Shown(item, index, total, name), most,
                Math.min(nanos, knownInGap + running));
    }

    private void end(Outcome outcome, String why, long now) {
        if (!paused) {
            gaps(now);
        }
        finish(outcome, why, now);
    }

    private void finish(Outcome outcome, String why, long now) {
        if (paused) {
            waited += now - waitStarted;
        } else {
            timeUntil(now);
        }
        if (waitLine != null && assetWait == null) {
            assetWait = files.waitEnds(waitBegan - started, now - waitBegan, waitBeganMillis, 0, false);
        }
        End end = new End(flow, outcome, why, now - started, step + 1, current(), ownFrameCount,
                interimFrameCount, interimSkipCount, largestGap, largestVisibleGap, javaModGap, unknownGap,
                List.copyOf(timed), waited, assetWait);
        timed = new ArrayList<>();
        waitLine = null;
        assetWait = null;
        flow = null;
        paused = false;
        known = null;
        clearItem();
        update();
        ended.accept(end);
    }

    private void stepLine(long now) {
        Line line = currentStepLine();
        log.add(line);
        if (report) {
            time(line, null, now);
        }
    }

    // a new line: the running one ends
    private void time(Line line, String path, long now) {
        timeUntil(now);
        timing = line;
        timingPath = path;
        timingStarted = now;
    }

    // the game's loop over a kind returned: the time after its last item is the step's own again
    private void runEnded(long now) {
        if (timing != null && !timing.isStep()) {
            time(currentStepLine(), null, now);
        }
    }

    private Line currentStepLine() {
        return Line.step(current(), step + 1, flow.steps().size());
    }

    private void timeUntil(long now) {
        if (timing != null) {
            // a hook on another thread takes its time before it waits for the lock
            timed.add(new Timed(timing, timingPath, timingStarted - started, Math.max(0, now - timingStarted)));
            timing = null;
        }
    }

    private Step current() {
        return flow.steps().get(step);
    }

    private void update() {
        active = flow != null;
        showing = active && !paused;
        ownFrames = showing && display && current().ownFrames();
        loadsWorld = showing && current() == Step.LOADING_WORLD;
        boolean track = loadsWorld && report;
        if (track != files.tracking()) {
            files.track(track, started, track ? clock.getAsLong() : 0);
        }
        if (!showing) {
            loadingJar = 0;
        }
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
