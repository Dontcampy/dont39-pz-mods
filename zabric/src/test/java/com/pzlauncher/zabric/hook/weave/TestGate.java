package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.HookGate;

import java.util.ArrayList;
import java.util.List;

public final class TestGate implements HookGate {
    public static final TestGate INSTANCE = new TestGate();

    public volatile boolean active = true;
    public volatile boolean skipPing;
    public final List<String> failures = new ArrayList<>();

    private TestGate() {
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public synchronized void failed(String what, Throwable t) {
        failures.add(what + ": " + t);
    }

    synchronized void reset() {
        active = true;
        skipPing = false;
        failures.clear();
    }
}
