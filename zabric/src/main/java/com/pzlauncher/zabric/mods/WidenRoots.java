package com.pzlauncher.zabric.mods;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.mods.impl.WidenRootsFile;
import com.pzlauncher.zabric.widener.AccessWidener;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Folders of mod folders outside the game's own, whose mods' widening lists Zabric reads at game start. A list can
 * only take effect at game start, so a folder added now is widened from the next launch on.
 * <p>
 * Call on the main thread, from a main entrypoint or later. Every method throws when Zabric did not run at game start
 * or its entrypoints are stopped for the session.
 */
public final class WidenRoots {
    private WidenRoots() {
    }

    /**
     * Widens the mods in {@code modsFolder}, an existing absolute folder, from the next launch on, for as long as it
     * exists; a launch that finds it gone drops it.
     */
    public static void add(Path modsFolder) throws IOException {
        Lifecycle.requireRunning("WidenRoots.add");
        WidenRootsFile.add(modsFolder);
    }

    /** Whether this launch's game start read the lists of the mods in {@code modsFolder}, an absolute path. */
    public static boolean scanned(Path modsFolder) {
        Lifecycle.requireRunning("WidenRoots.scanned");
        if (!modsFolder.isAbsolute()) {
            throw new IllegalArgumentException(modsFolder + " is not an absolute path");
        }
        return AccessWidener.scanned(modsFolder);
    }
}
