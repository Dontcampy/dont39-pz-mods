package com.pzlauncher.zabric.hook.impl;

import java.util.ArrayList;
import java.util.List;

public final class CapturedLog implements HookRuntime.Log {
    private final List<String> lines = new ArrayList<>();

    public static CapturedLog install() {
        CapturedLog log = new CapturedLog();
        HookRuntime.logTo(log);
        return log;
    }

    @Override
    public synchronized void info(String format, Object... args) {
        lines.add("info " + String.format(format, args));
    }

    @Override
    public synchronized void error(String format, Object... args) {
        lines.add("error " + String.format(format, args));
    }

    @Override
    public synchronized void exception(Throwable t, String message) {
        lines.add("error " + message + " | " + t);
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }
}
