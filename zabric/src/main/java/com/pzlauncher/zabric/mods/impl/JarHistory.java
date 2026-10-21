package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.mods.LoadPhase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What the loader loaded, pass by pass: its own records keep only the latest decision per jar, so the phase a jar
 * loaded in is lost once a later pass decides on it again.
 */
public final class JarHistory {
    /**
     * A loaded jar. {@code sha256}: what the loader recorded when it loaded, or null with {@code noSha256} saying why.
     */
    public record Entry(String modId, Path path, LoadPhase phase, String sha256, IllegalStateException noSha256) {
        /** The SHA-256; throws when the loader's records did not give it. */
        public String checkedSha256() {
            if (noSha256 != null) {
                throw new IllegalStateException(noSha256.getMessage(), noSha256);
            }
            return sha256;
        }
    }

    /** Which jar serves a package, for a fixed set of jars. */
    interface Packages {
        Path servingJar(String pkg) throws IOException;
    }

    interface Indexer {
        Packages index(List<Path> jars) throws IOException;
    }

    private static final Map<Path, Entry> loaded = new LinkedHashMap<>();
    private static List<Entry> lastPass;
    private static int passes;
    // the loaded jars change only with a pass, so their packages are read once per pass
    private static Packages packages;
    private static final Indexer LOADER = jars -> Loader.classPath(jars)::servingJar;
    static Indexer indexer = LOADER;

    private JarHistory() {
    }

    /**
     * At preLaunch, before the first pass. Logs nothing and touches no game class. {@code sha256}: the loader's hashes
     * by jar; what it throws fails only the calls that need a hash.
     */
    public static synchronized void preloaded(List<Loader.ModJar> jars, Supplier<Map<Path, String>> sha256) {
        Hashes hashes = Hashes.read(sha256);
        jars.forEach(j -> loaded.putIfAbsent(j.jar(), hashes.entry(j, LoadPhase.PRELOAD)));
        packages = null;
    }

    /** After each pass over the mod list, with the jars the loader loaded for its mods. */
    public static synchronized void passLoaded(List<Loader.ModJar> jars, Supplier<Map<Path, String>> sha256) {
        LoadPhase phase = passes == 0 ? LoadPhase.STARTUP : LoadPhase.LATER;
        passes++;
        Hashes hashes = Hashes.read(sha256);
        List<Entry> pass = new ArrayList<>();
        jars.forEach(j -> pass.add(loaded.computeIfAbsent(j.jar(), k -> hashes.entry(j, phase))));
        lastPass = List.copyOf(pass);
        packages = null;
    }

    public static synchronized List<Entry> loaded() {
        return checked(List.copyOf(loaded.values()));
    }

    public static synchronized List<Entry> lastPass() {
        if (lastPass == null) {
            throw new IllegalStateException("no pass over the mod list has finished yet");
        }
        return checked(lastPass);
    }

    /** The loaded jar the game runs the classes of {@code pkg} from, or null when no loaded jar holds a class of it. */
    public static synchronized Entry servingJar(String pkg) throws IOException {
        if (packages == null) {
            packages = indexer.index(List.copyOf(loaded.keySet()));
        }
        Path serving = packages.servingJar(pkg);
        if (serving == null) {
            return null;
        }
        Entry jar = loaded.get(serving);
        if (jar == null) {
            throw new IllegalStateException("the classes of " + pkg + " come from " + serving + ", which "
                    + Loader.name() + " " + Loader.version() + " did not record loading");
        }
        return jar;
    }

    private static List<Entry> checked(List<Entry> jars) {
        jars.forEach(Entry::checkedSha256);
        return jars;
    }

    private record Hashes(Map<Path, String> byJar, IllegalStateException error) {
        static Hashes read(Supplier<Map<Path, String>> sha256) {
            try {
                return new Hashes(sha256.get(), null);
            } catch (RuntimeException e) {
                return new Hashes(Map.of(), new IllegalStateException(e.getMessage(), e));
            }
        }

        Entry entry(Loader.ModJar j, LoadPhase phase) {
            String hash = byJar.get(j.jar());
            IllegalStateException problem = error != null ? error : hash != null ? null
                    : new IllegalStateException(Loader.name() + " " + Loader.version() + " recorded no SHA-256 for "
                    + j.jar() + " of " + j.modId());
            return new Entry(j.modId(), j.jar(), phase, hash, problem);
        }
    }

    // tests start from an empty JVM history
    static synchronized void reset() {
        loaded.clear();
        lastPass = null;
        passes = 0;
        packages = null;
        indexer = LOADER;
    }
}
