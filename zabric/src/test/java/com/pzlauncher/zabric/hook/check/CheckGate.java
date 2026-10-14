package com.pzlauncher.zabric.hook.check;

import com.pzlauncher.zabric.hook.HookGate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A named gate whose failures the weave check test reads. */
public final class CheckGate implements HookGate {
    public static final CheckGate INSTANCE = new CheckGate();

    public final List<String> failures = new ArrayList<>();
    public final AtomicInteger checks = new AtomicInteger();

    private CheckGate() {
    }

    @Override
    public boolean active() {
        checks.incrementAndGet();
        return true;
    }

    @Override
    public synchronized void failed(String what, Throwable t) {
        failures.add(what + ": " + t);
    }
}
