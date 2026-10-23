package com.dont39.pz.zbkt;

import com.dont39.pz.zbkt.selfcheck.SelfCheck;

import java.util.Properties;

/** This launch's Kotlin install and self-check: each runs once, and {@link Main} reports both. */
final class Install {
    private static volatile boolean atGameStart;
    private static Properties runtime;
    private static Throwable failure;
    private static boolean checked;
    private static Throwable checkFailure;

    private Install() {
    }

    /** From {@link PreMain}. */
    static synchronized void atGameStart() {
        // the swap needs Zabric's instrumentation: without it Main installs, and Zabric's restart notice names both mods
        if (!zabricRanAtGameStart()) {
            return;
        }
        atGameStart = true;
        try {
            runtime = KotlinSwap.install(true);
        } catch (Throwable t) {
            failure = t;
        }
    }

    /** Whether the install ran at game start, successful or not. */
    static boolean ranAtGameStart() {
        return atGameStart;
    }

    static synchronized boolean installedAtGameStart() {
        return atGameStart && runtime != null;
    }

    /**
     * The shipped versions; installs now unless the install ran at game start. A failure at game start is thrown again,
     * never retried: the swap may be half done.
     */
    static synchronized Properties runtime() throws Throwable {
        if (!atGameStart && runtime == null && failure == null) {
            try {
                runtime = KotlinSwap.install(false);
            } catch (Throwable t) {
                failure = t;
            }
        }
        if (failure != null) {
            throw failure;
        }
        return runtime;
    }

    /** The self-check's failure, null when it passed. */
    static synchronized Throwable selfCheck() {
        if (!checked) {
            checked = true;
            try {
                SelfCheck.run();
            } catch (Throwable t) {
                checkFailure = t;
            }
        }
        return checkFailure;
    }

    private static boolean zabricRanAtGameStart() {
        try {
            return com.pzlauncher.zabric.PreMain.ran();
        } catch (NoClassDefFoundError e) {
            return false; // Zabric's jar is on the classpath only when it was preloaded
        }
    }
}
