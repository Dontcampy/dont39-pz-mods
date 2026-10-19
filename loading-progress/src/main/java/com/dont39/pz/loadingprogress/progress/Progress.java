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

    /** What the log says when a flow ends. */
    public record End(Flow flow, Outcome outcome, String why, long nanos, int step, Step at, int ownFrames,
                      long largestGap, Step largestGapStep) {
    }

    /** What a frame shows; {@code step} counts from 1. */
    public record View(Flow flow, int step, Step current, Item item, int index, int total, String name) {
    }

    private final BooleanSupplier client;
    private final Consumer<End> ended;

    // read without the lock on hot paths
    private volatile boolean active;
    private volatile boolean showing;
    private volatile boolean ownFrames;
    private volatile long lastFrame;

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
    private long largestGap;
    private Step largestGapStep;
    private boolean launchSeen;
    private boolean stopped;

    private boolean modsLoading;
    private int modsLoaded;
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
                gap(now);
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
        reachLocked(Boundary.MODS, now);
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
                gap(now);
                lastFrame = now;
            }
        }
    }

    /** What to draw, or null when no flow shows. */
    public View view() {
        if (!showing) {
            return null;
        }
        synchronized (this) {
            return showing ? new View(flow, step + 1, current(), item, index, total, name) : null;
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
        clearItem();
        ownFrameCount = 0;
        largestGap = 0;
        largestGapStep = first;
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

    private void item(Item kind, int i, int n, String itemName) {
        if (flow != null && !paused && current().item() == kind) {
            item = kind;
            index = i;
            total = n;
            name = itemName;
        }
    }

    private void clearItem() {
        item = Item.NONE;
        index = 0;
        total = 0;
        name = null;
    }

    private void gap(long now) {
        long gap = now - lastFrame;
        if (gap > largestGap) {
            largestGap = gap;
            largestGapStep = current();
        }
    }

    private void end(Outcome outcome, String why, long now) {
        if (!paused) {
            gap(now);
        }
        finish(outcome, why, now);
    }

    private void finish(Outcome outcome, String why, long now) {
        End end = new End(flow, outcome, why, now - started, step + 1, current(), ownFrameCount, largestGap,
                largestGapStep);
        flow = null;
        paused = false;
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
    }

    private static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }
}
