package com.dont39.pz.loadingprogress.progress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The last lines of a flow, oldest dropped first: script and Lua files come by the thousand. Under Progress's lock. */
final class Log {
    private final Line[] lines;
    private int next;
    private int size;

    Log(int capacity) {
        lines = new Line[capacity];
    }

    void add(Line line) {
        lines[next] = line;
        next = (next + 1) % lines.length;
        size = Math.min(size + 1, lines.length);
    }

    void clear() {
        Arrays.fill(lines, null);
        next = 0;
        size = 0;
    }

    /** Oldest first. */
    List<Line> lines() {
        List<Line> copy = new ArrayList<>(size);
        for (int i = size; i > 0; i--) {
            copy.add(lines[Math.floorMod(next - i, lines.length)]);
        }
        return copy;
    }
}
