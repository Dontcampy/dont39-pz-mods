package com.pzlauncher.zabric.loader;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Java mod loader Zabric runs on (ZombieBuddy). This package is the only code that uses the loader's own types;
 * switching loaders means rewriting it. Usable at premain: no game classes.
 */
public final class Loader {
    private static final String NAME = "ZombieBuddy";

    private Loader() {
    }

    /**
     * What a {@link #keepPreloaded} call found: {@code wasCurrent} when the mod's entry did not have to change; where the
     * entries are; {@code reordered}, the new order when the stored one did not follow {@code require}, else empty;
     * {@code cycles}, the groups of entries that require each other and keep their stored order.
     */
    public record PreloadEntry(boolean wasCurrent, Path location, List<String> reordered, List<List<String>> cycles) {
    }

    /** A jar the loader loaded, with the id of the mod it loaded it for. */
    public record ModJar(String modId, Path jar) {
    }

    /**
     * A mod whose jar the loader loads on a client: {@code folder} the mod folder, {@code id} its mod id (null when its
     * {@code mod.info} has none), {@code jar} the jar {@code javaJarFile} names, {@code pkg} its {@code javaPkgName}.
     * {@code preload}: the loader or Zabric loads it at game start.
     */
    public record JavaMod(Path folder, String id, String name, Path jar, String pkg, boolean preload) {
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

    /**
     * The jars the loader recorded as preloaded or loaded for {@code modId} this launch, by canonical path. Throws
     * naming the loader version when its records cannot be read.
     */
    public static Set<Path> recordedJars(String modId) {
        return LoadStatus.jarsOf(modId);
    }

    /** The ids of the mod list the loader is loading; only valid inside that pass, e.g. from a mod's {@code Main}. */
    public static Set<String> currentPass() {
        return LoadStatus.currentPass();
    }

    /**
     * The SHA-256 (lowercase hex) the loader recorded for each jar it decided on, null for a jar it could not hash.
     * Throws naming the loader version when its records do not have them; {@link #preloadedJars} and
     * {@link #loadedThisPass} do not depend on this part of the records.
     */
    public static Map<Path, String> recordedSha256() {
        return LoadStatus.sha256();
    }

    /** The packages of {@code jars}, as the loader's class loader serves them. Reads each jar once. */
    public static ClassPath classPath(Collection<Path> jars) throws IOException {
        // the loader appends mod jars to the system class loader's search path
        return ClassPath.of(jars, ClassLoader.getSystemClassLoader());
    }

    /**
     * The mods in {@code modsFolder} whose jar the loader loads on a client, read by its rules. {@code versionFolder}:
     * the name of the version folder the game uses for a mod folder ({@code ZomboidFileSystem.getModVersionDirName}).
     * Throws naming the file that cannot be read.
     */
    public static List<JavaMod> javaMods(Path modsFolder, Function<Path, String> versionFolder) throws IOException {
        return ModInfo.javaMods(modsFolder, versionFolder);
    }

    /**
     * Every mod folder in {@code modsFolder} with a {@code mod.info} in any of its folders, with every id those files
     * may name, read more widely than the game does. For refusals: no version folder is known at premain.
     */
    public static Map<Path, Set<String>> modIds(Path modsFolder) throws IOException {
        return ModInfo.modIds(modsFolder);
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
     * <p>
     * Before the loader applies any jar's patches, at game start too, the hook loads the member types of the loaded
     * classes they name, so that those pass the access widener; what it cannot load is kept for
     * {@link #weavePrepEscaped}.
     */
    public static void onJarLoad(Instrumentation inst, JarListener listener) {
        JarLoadHook.install(inst, listener);
    }

    /**
     * Checks, at the end of the loader's pass over the mod list, that the {@link #onJarLoad} hook followed each jar the
     * pass recorded; what does not match is kept for {@link #jarLoadEscaped}. Never throws. Call from the
     * {@link #onModsLoaded} callback.
     */
    public static void jarPassEnded() {
        JarLoadHook.passEnded();
    }

    /**
     * What the {@link #onJarLoad} hook could not load before the loader applied a jar's patches since the last call,
     * or null. The loader's weaving went on as without it.
     */
    public static Throwable weavePrepEscaped() {
        return JarLoadHook.takeUnprepared();
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
     * The classes with global Lua functions ({@code @LuaMethod(global = true)}) the loader found in the jars it loaded.
     * It exposes them only when the game starts a Lua state, so a jar it loads after that has none in that state.
     * Throws naming the loader version when it does not list them.
     */
    public static List<Class<?>> globalLuaClasses() {
        try {
            return me.zed_0xff.zombie_buddy.Exposer.getClassesWithGlobalLuaMethod();
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException(NAME + " " + version() + " does not list its classes with global Lua"
                    + " functions (me.zed_0xff.zombie_buddy.Exposer.getClassesWithGlobalLuaMethod)", e);
        }
    }

    /**
     * Makes the loader run {@code modId} at game start from {@code versionDir}, the folder the game uses this session,
     * unless it already runs it from there or from a byte-identical copy, and keeps the preload order Zabric first, then
     * by {@code require}. {@code commonDir}: the mod's common folder, null when it has none. Throws when the mod cannot
     * be preloaded or the loader's settings cannot be updated.
     */
    public static PreloadEntry keepPreloaded(String modId, Path versionDir, Path commonDir) throws IOException {
        return PreloadConfig.keep(modId, versionDir, commonDir);
    }
}
