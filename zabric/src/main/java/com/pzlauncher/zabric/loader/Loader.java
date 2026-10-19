package com.pzlauncher.zabric.loader;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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

    /** A jar the loader loaded, with the id of the mod it loaded it for. */
    public record ModJar(String modId, Path jar) {
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
     * The jars the loader preloaded this launch. Valid until the mod list first loads; throws naming the loader version
     * when its records cannot be read.
     */
    public static List<ModJar> preloadedJars() {
        return LoadStatus.preloaded();
    }

    /**
     * The jars the loader approved and loaded in its latest pass over the mod list, for the mods of {@code pass}; also
     * while that pass runs, since the loader decides on every mod before it loads any. Throws naming the loader version
     * when its records cannot be read.
     */
    public static List<ModJar> loadedThisPass(Collection<String> pass) {
        return LoadStatus.loaded(pass);
    }

    /** The ids of the mod list the loader is loading; only valid inside that pass, e.g. from a mod's {@code Main}. */
    public static Set<String> currentPass() {
        return LoadStatus.currentPass();
    }

    /**
     * Calls {@code callback} with the pass's mod ids each time the loader has loaded the mod list (normal return only).
     * The weave check relies on the loader having registered the pass's patch transformers by then.
     * Throws naming the loader version when the hook cannot be installed. The hook never throws into the loader:
     * what {@code callback} throws is kept for {@link #modsLoadedEscaped}.
     */
    public static void onModsLoaded(Instrumentation inst, Consumer<List<String>> callback) {
        ModsLoadedHook.install(inst, callback);
    }

    /** What the {@link #onModsLoaded} callback threw into the hook since the last call, or null. */
    public static Throwable modsLoadedEscaped() {
        return ModsLoadedHook.takeEscaped();
    }

    /** Hears the jars of each pass over the mod list, on the loader's thread, outside any class transformation. */
    @FunctionalInterface
    public interface JarListener {
        /**
         * Before ({@code loaded} false) and after the loader loads jar {@code index} (from 1) of the {@code total} it
         * loads in this pass, for {@code modId}. The jars it loads are those it approved for the pass's mod list and its
         * preloaded mods; on a later pass, jars it loaded before return at once.
         */
        void jar(int index, int total, String modId, boolean loaded);
    }

    /**
     * Calls {@code listener} at each jar of each of the loader's passes over the mod list. Throws naming the loader
     * version when the hook cannot be installed. The hook never throws into the loader: a pass it cannot follow, or
     * what {@code listener} throws, is kept for {@link #jarLoadEscaped} and ends its calls for that pass.
     */
    public static void onJarLoad(Instrumentation inst, JarListener listener) {
        JarLoadHook.install(inst, listener);
    }

    /** What the {@link #onJarLoad} hook could not follow since the last call, or null. */
    public static Throwable jarLoadEscaped() {
        return JarLoadHook.takeEscaped();
    }

    /**
     * How many classes the loader's thread has transformed or loaded inside the jars of its passes so far, as the
     * {@link #onJarLoad} hook followed them; 0 without that hook. Any thread.
     */
    public static long jarWork() {
        return JarLoadHook.heartbeat();
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
