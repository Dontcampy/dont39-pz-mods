package com.dont39.pz.zabric.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

/** ZombieBuddy's agent state that mods built on Zabric need. Usable at premain: no game classes. */
public final class ZombieBuddyAgent {
    private ZombieBuddyAgent() {
    }

    /** ZombieBuddy's {@link Instrumentation}; throws naming the ZombieBuddy version when it cannot be read. */
    public static Instrumentation instrumentation() {
        Object value;
        try {
            // ZombieBuddy keeps it package-private; the game JRE has no jdk.attach to get another one
            Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            value = f.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new IllegalStateException(notExposed(), e);
        }
        if (!(value instanceof Instrumentation inst)) {
            throw new IllegalStateException(notExposed() + "; it is " + value);
        }
        return inst;
    }

    private static String notExposed() {
        return "ZombieBuddy " + version() + " does not expose its Instrumentation"
                + " (me.zed_0xff.zombie_buddy.Loader.g_instrumentation)";
    }

    private static String version() {
        try {
            return me.zed_0xff.zombie_buddy.ZombieBuddy.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }
}
