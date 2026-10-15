package com.pzlauncher.zabric.hook.weave;

import java.util.ArrayList;
import java.util.List;

/** What ran, in order. */
public final class Events {
    private static final List<String> events = new ArrayList<>();

    private Events() {
    }

    public static synchronized void add(String event) {
        events.add(event);
    }

    public static synchronized List<String> take() {
        List<String> taken = List.copyOf(events);
        events.clear();
        return taken;
    }
}
