package com.pzlauncher.zabric.mods;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.mods.impl.JarHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The Java mod jars the loader loaded in this JVM. A jar stays loaded until the game exits, so a mod whose jar changed
 * between passes over the mod list has both, and the class path decides which one runs ({@link #servingJar}).
 * <p>
 * Call on the main thread, from a main entrypoint or later. Every method throws when Zabric did not run at game start
 * or its entrypoints are stopped for the session, and when the loader's records do not read as Zabric expects.
 */
public final class JavaMods {
    /**
     * A loaded jar: the mod id the loader loaded it for, its real path, its SHA-256 (lowercase hex) when it loaded, and
     * when it loaded.
     */
    public record Jar(String modId, Path path, String sha256, LoadPhase phase) {
    }

    private JavaMods() {
    }

    /** Every jar the loader loaded this JVM, in the order it loaded them. */
    public static List<Jar> loaded() {
        Lifecycle.requireRunning("JavaMods.loaded");
        return JarHistory.loaded();
    }

    /**
     * The jars the loader approved and loaded for the mods of the latest pass over the mod list, including those it
     * had loaded in an earlier pass. Mods preloaded at game start count only when they are in that mod list.
     */
    public static List<Jar> lastPass() {
        Lifecycle.requireRunning("JavaMods.lastPass");
        return JarHistory.lastPass();
    }

    /**
     * The loaded jar whose classes of {@code pkg} (exactly that package, not its subpackages) the game runs, or null
     * when no loaded jar holds a class of it. Throws when they come from more than one jar.
     */
    public static Jar servingJar(String pkg) throws IOException {
        Lifecycle.requireRunning("JavaMods.servingJar");
        List<Jar> jars = JarHistory.loaded();
        Path serving = Loader.servingJar(pkg, jars.stream().map(Jar::path).toList());
        if (serving == null) {
            return null;
        }
        return jars.stream().filter(j -> j.path().equals(serving)).findFirst().orElseThrow(() ->
                new IllegalStateException("the classes of " + pkg + " come from " + serving + ", which " + Loader.name()
                        + " " + Loader.version() + " did not record loading"));
    }
}
