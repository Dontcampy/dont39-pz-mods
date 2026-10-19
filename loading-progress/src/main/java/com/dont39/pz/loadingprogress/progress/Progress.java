package com.dont39.pz.loadingprogress.progress;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The running flow, its step and item, and its frame gaps. Game-free: the hooks pass in what they read of the game and
 * the time. Called from the game thread and the world-loading thread; hot callers check the volatile flags first. A
 * step that begins out of order throws, which stops the mod's hooks.
 */
public final class Progress {
    /** At most this often an own frame; about 20 per second. */
    static final long FRAME_INTERVAL = TimeUnit.MILLISECONDS.toNanos(50);

    // the connection states ConnectToServer.lua treats as a failed join
    private static final Set<String> FAILED_STATES = Set.of("Failed", "Disconnected");

    public enum Outcome {
        DONE, STOPPED, LEFT
    }

    /**
     * What the log says when a flow ends. {@code largestGap}: between frames the game thread handed over; the others
     * between those and interim frames together: {@code largestVisibleGap} of all, {@code javaModGap} of those the
     * Java mod jars showed at (null when none showed), {@code unknownGap} the one with the most time outside
     * {@link Known} calls. {@code interimSkips}: interim frames or their copies skipped for an OpenGL error the game
     * left pending.
     */
    public record End(Flow flow, Outcome outcome, String why, long nanos, int step, Step at, int ownFrames,
                      int interimFrames, int interimSkips, Gap largestGap, Gap largestVisibleGap, Gap javaModGap,
                      Gap unknownGap) {
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

    /** An item of the second line. */
    public record Shown(Item item, int index, int total, String name) {
    }

    /**
     * What a frame shows; {@code step} counts from 1. {@code fill}: the bar's filled fraction, by step. {@code sub}: the
     * sub-bar's, by a counted item, or negative when none shows. While a Java mod jar loads, {@code jar} is its
     * {@link #loadingJar()} and {@code jarEnd} the fraction the sub-bar reaches once it loaded; otherwise 0 and
     * {@code sub}.
     */
    public record View(Flow flow, int step, Step current, Item item, int index, int total, String name, double fill,
                       double sub, long jar, double jarEnd) {
    }

    private final BooleanSupplier client;
    private final Consumer<End> ended;

    // read without the lock on hot paths
    private volatile boolean active;
    private volatile boolean showing;
    private volatile boolean ownFrames;
    private volatile long lastFrame;
    private volatile long loadingJar;

    private Flow flow;
    private int step;
    // a game start waits in the menu between its Lua reset and the world load
    private boolean paused;
    private long started;
    private Item item = Item.NONE;
    private int index;
    private int total;
    private String name;
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

    /** {@code client}: flows start only where it is true. {@code ended} hears each flow's end, under the lock. */
    public Progress(BooleanSupplier client, Consumer<End> ended) {
        this.client = client;
        this.ended = ended;
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

    /** A Lua event fires. */
    public void event(String event, long now) {
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
            item(Item.EVENT, 0, 0, event);
        }
    }

    /** The network connection's state changed. */
    public void connectionState(String state, long now) {
        if (!active || state == null || !FAILED_STATES.contains(state)) {
            return;
        }
        synchronized (this) {
            joinFailedLocked(now);
        }
    }

    /** The mod list loads; {@link #mod} counts its mods until {@link #modsLoaded()}. */
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
        if (item(Item.JAVA_MOD, index, total, id) && !loaded) {
            loadingJar = ++jarCount;
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

    public synchronized void modsLoaded() {
        modsLoading = false;
    }

    /** {@code total}: the mods the list loads. */
    public void mod(String id, int total) {
        if (!active) {
            return;
        }
        synchronized (this) {
            if (modsLoading && flow != null) {
                modsLoaded = counted(modsLoaded, total, "mod " + id);
                item(Item.MOD, modsLoaded, total, id);
            }
        }
    }

    /**
     * The script files load; {@link #script} counts them until {@link #scriptsLoaded()}. {@code path}: the game's
     * current script file, left from an earlier load.
     */
    public synchronized void scriptsLoading(String path, long now) {
        scriptsLoading = true;
        scriptsLoaded = 0;
        scriptPath = path;
        reachLocked(Boundary.SCRIPTS, now);
    }

    public synchronized void scriptsLoaded() {
        scriptsLoading = false;
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
                scriptsLoaded = counted(scriptsLoaded, total, "script file " + path);
                item(Item.SCRIPT, scriptsLoaded, total, file);
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

    public synchronized void luaLoaded() {
        luaLoading = false;
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
        luaRun = counted(luaRun, luaTotal, "Lua file " + file);
        item(Item.LUA, luaRun, luaTotal, fileName(file));
    }

    public synchronized void luaRan() {
        if (luaLoading && luaDepth > 0) {
            luaDepth--;
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

    /** A frame was handed to the render thread. */
    public void frameHandedOver(long now) {
        if (!showing) {
            return;
        }
        synchronized (this) {
            if (showing) {
                gaps(now);
                lastFrame = now;
                visible(now);
            }
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
            double sub = sub();
            long jar = loadingJar;
            double jarEnd = jar == 0 ? sub : (double) javaMod / javaModTotal;
            return new View(flow, step + 1, current(), item, index, total, name, (step + 1.0) / flow.steps().size(),
                    sub, jar, jarEnd);
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
        if (stopped || !client.getAsBoolean()) {
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
            lastFrame = now;
            lastVisible = now;
            clearKnownGap();
        } else if (i < step) {
            throw new IllegalStateException(flow.label() + ": " + next + " began after " + current());
        }
        if (i > step) {
            step = i;
            clearItem();
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

    private int counted(int done, int total, String what) {
        if (done >= total) {
            throw new IllegalStateException(flow.label() + ": " + what + " is past the " + total + " counted at "
                    + current());
        }
        return done + 1;
    }

    // whether the item shows
    private boolean item(Item kind, int i, int n, String itemName) {
        if (flow == null || paused || !current().shows(kind)) {
            return false;
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
    }

    private void clearJavaMods() {
        javaMod = 0;
        javaModTotal = 0;
        javaModLoading = false;
    }

    // the counted item's fraction: a loading jar counts from the previous one's end
    private double sub() {
        if (!item.counted() || total == 0) {
            return -1;
        }
        int done = item == Item.JAVA_MOD && javaModLoading ? index - 1 : index;
        return (double) done / total;
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
        End end = new End(flow, outcome, why, now - started, step + 1, current(), ownFrameCount,
                interimFrameCount, interimSkipCount, largestGap, largestVisibleGap, javaModGap, unknownGap);
        flow = null;
        paused = false;
        known = null;
        clearItem();
        update();
        ended.accept(end);
    }

    private Step current() {
        return flow.steps().get(step);
    }

    private void update() {
        active = flow != null;
        showing = active && !paused;
        ownFrames = showing && current().ownFrames();
        if (!showing) {
            loadingJar = 0;
        }
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
