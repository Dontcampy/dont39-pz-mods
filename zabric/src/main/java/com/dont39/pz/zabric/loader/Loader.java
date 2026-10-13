package com.dont39.pz.zabric.loader;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Path;

/**
 * The Java mod loader Zabric runs on (ZombieBuddy). This package is the only code that uses the loader's own types;
 * switching loaders means rewriting it. Usable at premain: no game classes.
 */
public final class Loader {
    private static final String NAME = "ZombieBuddy";

    private Loader() {
    }

    /** What a {@link #keepPreloaded} call found: {@code wasCurrent} when nothing had to change; where the entry is. */
    public record PreloadEntry(boolean wasCurrent, Path location) {
    }

    /** The loader's name, for messages. */
    public static String name() {
        return NAME;
    }

    /** The loader's version, or "(unknown version)". */
    public static String version() {
        try {
            return me.zed_0xff.zombie_buddy.ZombieBuddy.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }

    /** The loader's {@link Instrumentation}; throws naming the loader version when it cannot be read. */
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
        return NAME + " " + version() + " does not expose its Instrumentation"
                + " (me.zed_0xff.zombie_buddy.Loader.g_instrumentation)";
    }

    /**
     * Makes the loader run {@code modId} at game start from {@code versionDir}, the folder the game uses this session,
     * unless it already runs it from there or from a byte-identical copy. {@code commonDir}: the mod's common folder,
     * null when it has none. Throws when the mod cannot be preloaded or the loader's settings cannot be updated.
     */
    public static PreloadEntry keepPreloaded(String modId, Path versionDir, Path commonDir) throws IOException {
        return PreloadConfig.keep(modId, versionDir, commonDir);
    }
}
