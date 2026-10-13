package com.dont39.pz.zbkt;

import com.dont39.pz.zbkt.selfcheck.SelfCheck;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.util.Properties;

/**
 * Whether the current Kotlin runtime is in place. Kotlin is installed only at game start ({@link PreMain}); on a launch
 * without it, dependents run on the game's Kotlin 1.4.10 and must do nothing.
 * <p>
 * Java only, with no reference to any kotlin.* type: dependents' gates call it on such a launch
 * (checkBootstrapClasses).
 */
public final class ZombieBuddyKotlin {
    static final String PREFIX = "[ZombieBuddyKotlin] ";
    private static final String NOT_PRELOADED = "ZombieBuddyKotlin did not run at game start: it registers itself as a"
            + " ZombieBuddy preload mod on its first launch; restart the game once";

    private static volatile boolean ready;

    private ZombieBuddyKotlin() {
    }

    /** True once Kotlin was installed at game start and the self-check passed; the only readiness check dependents use. */
    public static boolean isReady() {
        return ready;
    }

    static void report() {
        String why = PreMain.ran() ? PreMain.problem() : NOT_PRELOADED;
        Throwable error = PreMain.error();
        if (why == null) {
            try {
                SelfCheck.run();
            } catch (Throwable t) {
                error = t;
                why = "self-check failed: " + t;
            }
        }
        if (why != null) {
            if (error != null) {
                DebugType.Mod.printException(error, PREFIX + "not ready: " + why, LogSeverity.Error);
            } else {
                DebugType.Mod.warn(PREFIX + "not ready: %s", why);
            }
            return;
        }
        ready = true;
        Properties runtime = PreMain.runtime();
        DebugType.Mod.println(PREFIX + "ready: Kotlin %s, kotlinx-coroutines %s, kotlinx-serialization %s",
                runtime.getProperty("version"), runtime.getProperty("coroutines"), runtime.getProperty("serialization"));
    }
}
