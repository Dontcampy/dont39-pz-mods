package com.dont39.pz.syncguard.game;

import zombie.debug.DebugType;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * The mod's launch options (JVM system properties), read once at the main entrypoint: a summary period for load tests
 * and two debug switches for the in-game tests. Off by default; a switch that is on says so once.
 */
public final class Settings {
    private static final String SUMMARY = "syncguard.summarySeconds";
    private static final int SUMMARY_DEFAULT = 600;
    private static final String DROP_DELTA = "syncguard.debugDropDelta";
    private static final String FIELD_DIFF = "syncguard.debugFieldDiff";

    private static int summarySeconds = SUMMARY_DEFAULT;
    private static int dropDelta;
    private static boolean fieldDiff;

    private Settings() {
    }

    public static void load() {
        summarySeconds = positive(SUMMARY, SUMMARY_DEFAULT);
        dropDelta = positive(DROP_DELTA, 0);
        fieldDiff = Boolean.parseBoolean(System.getProperty(FIELD_DIFF));
    }

    /** {@code key=value} summary lines every this many seconds, and at the end of a connection. */
    public static int summarySeconds() {
        return summarySeconds;
    }

    /** Client: drops every n-th incoming container or item update, to test detection and repair; 0 when off. */
    public static int dropDelta() {
        return dropDelta;
    }

    /** Client: logs which digest fields differ around each repair. */
    public static boolean fieldDiff() {
        return fieldDiff;
    }

    /** Logs the debug switches that are on; called once on a client. */
    public static void reportClientSwitches() {
        if (dropDelta > 0) {
            DebugType.Mod.warn(PREFIX + "debug: drops every %d. incoming container or item update of the game"
                    + " (-D%s), to test repairs; leave it off for play", dropDelta, DROP_DELTA);
        }
        if (fieldDiff) {
            DebugType.Mod.println(PREFIX + "debug: logs the digest fields that differ around each repair (-D%s)",
                    FIELD_DIFF);
        }
    }

    private static int positive(String property, int fallback) {
        String value = System.getProperty(property);
        if (value == null) {
            return fallback;
        }
        try {
            int n = Integer.parseInt(value.trim());
            if (n > 0) {
                return n;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        DebugType.Mod.warn(PREFIX + "-D%s=%s is not a whole number above 0; it stays %d", property, value, fallback);
        return fallback;
    }
}
