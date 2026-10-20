package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.mods.JavaMods.Jar;
import com.pzlauncher.zabric.mods.LoadPhase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the loader loaded, pass by pass: its own records keep only the latest decision per jar, so the phase a jar
 * loaded in is lost once a later pass decides on it again.
 */
public final class JarHistory {
    private static final Map<Path, Jar> loaded = new LinkedHashMap<>();
    private static List<Jar> lastPass;
    private static int passes;

    private JarHistory() {
    }

    /** At preLaunch, before the first pass. Logs nothing and touches no game class. */
    public static synchronized void preloaded(List<Loader.ModJar> jars) {
        jars.forEach(j -> loaded.putIfAbsent(j.jar(), jar(j, LoadPhase.PRELOAD)));
    }

    /** After each pass over the mod list, with the jars the loader loaded for its mods. */
    public static synchronized void passLoaded(List<Loader.ModJar> jars) {
        LoadPhase phase = passes == 0 ? LoadPhase.STARTUP : LoadPhase.LATER;
        passes++;
        List<Jar> pass = new ArrayList<>();
        jars.forEach(j -> pass.add(loaded.computeIfAbsent(j.jar(), k -> jar(j, phase))));
        lastPass = List.copyOf(pass);
    }

    public static synchronized List<Jar> loaded() {
        return checked(List.copyOf(loaded.values()));
    }

    public static synchronized List<Jar> lastPass() {
        if (lastPass == null) {
            throw new IllegalStateException("no pass over the mod list has finished yet");
        }
        return checked(lastPass);
    }

    private static Jar jar(Loader.ModJar j, LoadPhase phase) {
        return new Jar(j.modId(), j.jar(), j.sha256(), phase);
    }

    private static List<Jar> checked(List<Jar> jars) {
        for (Jar j : jars) {
            if (j.sha256() == null) {
                throw new IllegalStateException(Loader.name() + " " + Loader.version() + " recorded no SHA-256 for "
                        + j.path() + " of " + j.modId());
            }
        }
        return jars;
    }

    // tests start from an empty JVM history
    static synchronized void reset() {
        loaded.clear();
        lastPass = null;
        passes = 0;
    }
}
