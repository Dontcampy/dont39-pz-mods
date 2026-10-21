package com.pzlauncher.zabric.mods;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.mods.impl.JarHistory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The Java mod jars the loader loaded in this JVM, and the Java mods in a mod folder. A jar stays loaded until the game
 * exits, so a mod whose jar changed between passes over the mod list has both, and the class path decides which one
 * runs ({@link #servingJar}).
 * <p>
 * Call from a main entrypoint or later, on any thread. Every method throws when Zabric did not run at game start or
 * its entrypoints are stopped for the session.
 */
public final class JavaMods {
    /** A loaded jar: the mod id the loader loaded it for, its real path, its SHA-256 when it loaded, and when. */
    public static final class Jar {
        private final JarHistory.Entry entry;

        private Jar(JarHistory.Entry entry) {
            this.entry = entry;
        }

        public String modId() {
            return entry.modId();
        }

        public Path path() {
            return entry.path();
        }

        /**
         * Lowercase hex. Throws when the loader's records did not give it ({@link #loaded} and {@link #lastPass}
         * throw then too).
         */
        public String sha256() {
            return entry.checkedSha256();
        }

        public LoadPhase phase() {
            return entry.phase();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Jar other && modId().equals(other.modId()) && path().equals(other.path())
                    && phase() == other.phase() && Objects.equals(entry.sha256(), other.entry.sha256());
        }

        @Override
        public int hashCode() {
            return Objects.hash(modId(), path(), phase(), entry.sha256());
        }

        @Override
        public String toString() {
            return "Jar[" + modId() + ", " + path() + ", " + phase() + ", sha256 " + entry.sha256() + "]";
        }
    }

    /**
     * A mod whose jar the loader loads on a client, as the files in its folder say: {@link #folder()} the mod folder,
     * {@link #id()} its mod id (null when its {@code mod.info} has none), {@link #name()} its name (its id or folder name
     * when it has none), {@link #jar()} the jar its {@code javaJarFile} names, {@link #pkg()} its {@code javaPkgName}.
     * {@link #preload()}: the loader or Zabric runs it at game start, before any pass over the mod list.
     */
    public static final class Mod {
        private final Loader.JavaMod mod;

        private Mod(Loader.JavaMod mod) {
            this.mod = mod;
        }

        public Path folder() {
            return mod.folder();
        }

        public String id() {
            return mod.id();
        }

        public String name() {
            return mod.name();
        }

        public Path jar() {
            return mod.jar();
        }

        public String pkg() {
            return mod.pkg();
        }

        public boolean preload() {
            return mod.preload();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Mod other && mod.equals(other.mod);
        }

        @Override
        public int hashCode() {
            return mod.hashCode();
        }

        @Override
        public String toString() {
            return "Mod[" + folder() + ", id " + id() + ", " + jar() + ", " + pkg() + (preload() ? ", preload" : "")
                    + "]";
        }
    }

    private JavaMods() {
    }

    /** Every jar the loader loaded this JVM, in the order it loaded them. */
    public static List<Jar> loaded() {
        Lifecycle.requireRunning("JavaMods.loaded");
        return JarHistory.loaded().stream().map(Jar::new).toList();
    }

    /**
     * The jars the loader approved and loaded for the mods of the latest pass over the mod list, including those it
     * had loaded in an earlier pass. Mods preloaded at game start count only when they are in that mod list.
     */
    public static List<Jar> lastPass() {
        Lifecycle.requireRunning("JavaMods.lastPass");
        return JarHistory.lastPass().stream().map(Jar::new).toList();
    }

    /**
     * The loaded jar whose classes of {@code pkg} (exactly that package, not its subpackages) the game runs, or null
     * when no loaded jar holds a class of it. Throws when they come from more than one jar, or from a jar the loader did
     * not record loading.
     */
    public static Jar servingJar(String pkg) throws IOException {
        Lifecycle.requireRunning("JavaMods.servingJar");
        JarHistory.Entry entry = JarHistory.servingJar(pkg);
        return entry == null ? null : new Jar(entry);
    }

    /**
     * The mods in {@code modsFolder} (a folder of mod folders) whose jar the loader loads on a client, read by the
     * loader's rules: none when the folder does not exist. {@code versionFolder} gives the name of the version folder
     * the game uses for a mod folder ({@code ZomboidFileSystem.getModVersionDirName}). Throws naming a file that cannot
     * be read.
     */
    public static List<Mod> inFolder(Path modsFolder, Function<Path, String> versionFolder) throws IOException {
        Lifecycle.requireRunning("JavaMods.inFolder");
        return Loader.javaMods(modsFolder, versionFolder).stream().map(Mod::new).toList();
    }
}
