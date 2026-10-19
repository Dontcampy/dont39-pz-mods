package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ModHooks;

import java.util.List;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

final class ModState implements ModHooks {
    private final String modId;
    private final HookGate defaultGate = new DefaultGate();
    private boolean inPass;
    // until the pass-time weave check: hooks must not run on unchecked targets
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
            // a throwing default gate has nowhere left to report to
            if (gate != defaultGate) {
                if (reporting != t) {
                    reporting.addSuppressed(t);
                }
                failed(defaultGate, "reporting an error to " + gate.getClass().getName() + " (" + what + ")",
                        reporting);
            }
        }
    }

    synchronized boolean firstPass() {
        if (checked) {
            return false;
        }
        checked = true;
        return true;
    }

    synchronized boolean enterPass() {
        boolean entering = !inPass;
        if (entering) {
            inPass = true;
            held = true;
            update();
        }
        return entering;
    }

    synchronized void release() {
        held = false;
        update();
    }

    synchronized void leavePass() {
        inPass = false;
        held = false;
        update();
    }

    synchronized boolean live() {
        return inPass && !stopped;
    }

    List<HookListing> listings() {
        return listings;
    }

    void listings(List<HookListing> listings) {
        this.listings = List.copyOf(listings);
    }

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
            HookRuntime.log().exception(t, PREFIX + modId + ": " + state + what);
        }

        @Override
        public String toString() {
            return "the default gate of " + modId;
        }
    }
}
