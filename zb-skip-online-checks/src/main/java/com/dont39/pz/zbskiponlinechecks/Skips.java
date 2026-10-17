package com.dont39.pz.zbskiponlinechecks;

import me.zed_0xff.zombie_buddy.Agent;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

/** The requests the advice skipped. Public only because the advice is inlined into ZombieBuddy's classes. */
public final class Skips {
    public enum Target {
        BAN_STATUS("Steam ban status"),
        AUTHORS("known authors"),
        PROFILE_KEYS("Steam profile keys");

        private final String label;

        Target(String label) {
            this.label = label;
        }
    }

    private static final Map<Target, Integer> atGameStart = new EnumMap<>(Target.class);
    private static final Map<Target, Integer> withModLists = new EnumMap<>(Target.class);
    private static Throwable error;

    private Skips() {
    }

    /** Called by the advice. Never throws into ZombieBuddy: the mod list loading around it depends on it. */
    public static synchronized void count(Target target) {
        try {
            (inAgentPremain() ? atGameStart : withModLists).merge(target, 1, Integer::sum);
        } catch (Throwable t) {
            if (error == null) {
                error = t;
            }
        }
    }

    /** The counts, for the log. */
    public static synchronized String report() {
        return "at game start: " + counts(atGameStart) + "; with mod lists: " + counts(withModLists);
    }

    /** The first error counting a skip, or null; the skip itself happened. */
    public static synchronized Throwable error() {
        return error;
    }

    // ZombieBuddy's agent premain is on the stack only before the game's main method runs
    private static boolean inAgentPremain() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.anyMatch(f -> f.getDeclaringClass() == Agent.class));
    }

    private static String counts(Map<Target, Integer> counts) {
        return Arrays.stream(Target.values())
                .map(t -> t.label + " " + counts.getOrDefault(t, 0))
                .collect(Collectors.joining(", "));
    }
}
