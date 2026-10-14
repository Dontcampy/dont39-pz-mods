package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ModHooks;

import java.util.List;

/** The one {@link ModHooks}. */
final class ModState implements ModHooks {
    private final String modId;
    private final HookGate defaultGate = new DefaultGate();
    private boolean inPass;
    // entered the pass, waiting for the pass-time weave check: its hooks must not run on unchecked targets
    private boolean held;
    private boolean stopped;
    private boolean checked;
    private volatile List<HookListing> listings = List.of();
    // read on every hooked call, from any thread
    private volatile boolean open;

    ModState(String modId) {
        this.modId = modId;
    }

    @Override
    public String modId() {
        return modId;
    }

    @Override
    public boolean open() {
        return open;
    }

    @Override
    public boolean active(HookGate gate) {
        if (!open) {
            return false;
        }
        try {
            return gate.active();
        } catch (Throwable t) {
            failed(gate, "checking whether " + gate.getClass().getName() + " is active", t);
            return false;
        }
    }

    @Override
    public HookGate defaultGate() {
        return defaultGate;
    }

    @Override
    public void failed(HookGate gate, String what, Throwable t) {
        try {
            gate.failed(what, t);
        } catch (Throwable reporting) {
            // a failing default gate is dropped: nowhere left to report, and never into the hooked method
            if (gate != defaultGate) {
                if (reporting != t) {
                    reporting.addSuppressed(t);
                }
                failed(defaultGate, "reporting an error to " + gate.getClass().getName() + " (" + what + ")",
                        reporting);
            }
        }
    }

    /** True the first time only: the mod's targets are checked once per session. */
    synchronized boolean firstPass() {
        if (checked) {
            return false;
        }
        checked = true;
        return true;
    }

    /** True when the mod was not in the previous pass; such a mod stays closed until {@link #release()}. */
    synchronized boolean enterPass() {
        boolean entering = !inPass;
        if (entering) {
            inPass = true;
            held = true;
            update();
        }
        return entering;
    }

    /** Opens a mod {@link #enterPass()} held, unless it stopped meanwhile. */
    synchronized void release() {
        held = false;
        update();
    }

    synchronized void leavePass() {
        inPass = false;
        held = false;
        update();
    }

    /** In the pass and not stopped: open, or held until the pass-time weave check is done. */
    synchronized boolean live() {
        return inPass && !stopped;
    }

    /** The mod's hook listings, read at its first pass. */
    List<HookListing> listings() {
        return listings;
    }

    void listings(List<HookListing> listings) {
        this.listings = List.copyOf(listings);
    }

    /** Closes the mod's hooks for the session; true the first time. */
    synchronized boolean stop() {
        boolean first = !stopped;
        stopped = true;
        update();
        return first;
    }

    private void update() {
        open = inPass && !held && !stopped;
    }

    private final class DefaultGate implements HookGate {
        @Override
        public boolean active() {
            return open;
        }

        @Override
        public void failed(String what, Throwable t) {
            String state = stop() ? "hooks stopped after an error while " : "hooks stopped; then an error while ";
            HookRuntime.log().exception(t, HookRuntime.PREFIX + modId + ": " + state + what);
        }

        @Override
        public String toString() {
            return "the default gate of " + modId;
        }
    }
}
