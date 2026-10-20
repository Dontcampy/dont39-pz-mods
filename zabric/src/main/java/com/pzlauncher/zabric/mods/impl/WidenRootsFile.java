package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.internal.AtomicWrite;
import com.pzlauncher.zabric.internal.GameFolders;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * The folders of mod folders that mods registered for widening ({@code widen-roots.txt}, one absolute path per line).
 * Read at premain by the widener's scan; a line naming no folder is dropped at MAIN.
 */
public final class WidenRootsFile {
    static final String FILE = "widen-roots.txt";

    /** {@code dropped}: the lines that name no existing absolute folder. */
    record Read(List<Path> folders, List<String> dropped) {
    }

    private static Read atGameStart;
    private static Throwable readError;

    private WidenRootsFile() {
    }

    public static Path file() {
        return GameFolders.zabricDir().resolve(FILE);
    }

    /**
     * Called by the widener's scan at premain: the registered folders that exist. Never throws and logs nothing: a
     * file it cannot read is reported at MAIN.
     */
    public static synchronized List<Path> atGameStart() {
        if (atGameStart == null) {
            try {
                atGameStart = read(file());
            } catch (Throwable t) {
                readError = t;
                atGameStart = new Read(List.of(), List.of());
            }
        }
        return atGameStart.folders();
    }

    /** Called by Zabric's Main: drops the lines premain found naming no folder, and reports what it could not read. */
    public static synchronized void report() {
        if (readError != null) {
            DebugType.Mod.printException(readError, PREFIX + "widen roots: cannot read " + file() + ", so the folders"
                    + " in it were not widened: " + readError, LogSeverity.Error);
            return;
        }
        if (atGameStart == null || atGameStart.dropped().isEmpty()) {
            return;
        }
        try {
            drop(file(), atGameStart.dropped());
            DebugType.Mod.println(PREFIX + "widen roots: dropped from %s: %s", file(), atGameStart.dropped());
        } catch (Throwable t) {
            DebugType.Mod.printException(t, PREFIX + "widen roots: cannot drop " + atGameStart.dropped() + " from "
                    + file(), LogSeverity.Error);
        }
    }

    /** Adds {@code folder}, an existing absolute folder, unless it is listed already. */
    public static synchronized void add(Path folder) throws IOException {
        add(file(), folder);
    }

    static void add(Path file, Path folder) throws IOException {
        if (!folder.isAbsolute()) {
            throw new IllegalArgumentException(folder + " is not an absolute path");
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException(folder + " is not a folder");
        }
        String line = folder.normalize().toString();
        List<String> lines = lines(file);
        if (lines.contains(line)) {
            return;
        }
        lines.add(line);
        write(file, lines);
    }

    static Read read(Path file) throws IOException {
        List<Path> folders = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (String line : lines(file)) {
            Path folder;
            try {
                folder = Path.of(line);
            } catch (InvalidPathException e) {
                dropped.add(line);
                continue;
            }
            if (folder.isAbsolute() && Files.isDirectory(folder)) {
                folders.add(folder.normalize());
            } else {
                dropped.add(line);
            }
        }
        return new Read(List.copyOf(folders), List.copyOf(dropped));
    }

    static void drop(Path file, List<String> dropped) throws IOException {
        List<String> lines = lines(file);
        if (lines.removeAll(dropped)) {
            write(file, lines);
        }
    }

    private static List<String> lines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        Set<String> lines = new LinkedHashSet<>();
        Files.readAllLines(file, StandardCharsets.UTF_8).forEach(l -> {
            if (!l.isBlank()) {
                lines.add(l.strip());
            }
        });
        return new ArrayList<>(lines);
    }

    private static void write(Path file, List<String> lines) throws IOException {
        if (lines.isEmpty()) {
            Files.deleteIfExists(file);
        } else {
            AtomicWrite.write(file, String.join("\n", lines) + "\n");
        }
    }
}
