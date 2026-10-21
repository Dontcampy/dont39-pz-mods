package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.internal.GameSide;
import com.pzlauncher.zabric.loader.Loader;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * This launch's override of Workshop item folders. Premain cannot tell a client from a dedicated server, so it reads
 * both sides' request files, widens their folders and installs {@link ItemFoldersHook}; the game's main method (the
 * preLaunch hook) takes its own side's file, and only then does the hook replace anything. Reported at MAIN.
 */
public final class LaunchOverrides {
    static final String TARGET = "zombie.ZomboidFileSystem";

    /** A request file found at premain: its bytes and request when valid, else why not. */
    private record Seen(Path file, byte[] content, Map<String, Path> request, String problem, Throwable error) {
    }

    private static volatile Path zabricDir;
    private static volatile Map<GameSide, Seen> seen = Map.of();
    private static volatile boolean sideKnown;
    private static volatile Map<String, Path> request = Map.of();
    private static volatile String discarded;
    private static volatile Throwable discardedError;

    // after MAIN, what changes is logged as it happens
    private static final Set<String> listed = new HashSet<>();
    private static Throwable failure;
    private static boolean reported;

    private LaunchOverrides() {
    }

    /**
     * Called by Zabric's PreMain before the widener scans, which reads the requested folders' lists. Deletes nothing.
     * Never throws, logs nothing and touches no game class.
     */
    public static void read() {
        try {
            read(GameFolders.zabricDir(), GameFolders.workshopContent());
        } catch (Throwable t) {
            discarded = "no request read: " + t;
            discardedError = t;
        }
    }

    static void read(Path dir, List<Path> workshopContent) {
        zabricDir = dir;
        Map<GameSide, Seen> found = new EnumMap<>(GameSide.class);
        for (GameSide side : GameSide.values()) {
            Path file = OverrideRequest.file(dir, side);
            try {
                OverrideRequest.Peeked peeked = OverrideRequest.peek(file, workshopContent);
                if (peeked != null) {
                    found.put(side, new Seen(file, peeked.content(), peeked.request(), null, null));
                }
            } catch (OverrideRequest.Invalid e) {
                found.put(side, new Seen(file, null, Map.of(), e.getMessage(), null));
            } catch (Throwable t) {
                found.put(side, new Seen(file, null, Map.of(), t.toString(), t));
            }
        }
        seen = found;
    }

    /**
     * Called by Zabric's PreMain; installs the hook only when a side has a valid request. Never throws and logs
     * nothing.
     */
    public static void install() {
        install(Loader::instrumentation, TARGET);
    }

    static void install(Supplier<Instrumentation> instrumentation, String target) {
        if (folders().isEmpty()) {
            return;
        }
        try {
            ItemFoldersHook.install(instrumentation.get(), target);
        } catch (Throwable t) {
            fail(new IllegalStateException("cannot install its hook on " + target + "." + ItemFoldersHook.METHOD
                    + ": " + t, t));
        }
    }

    /** The folders of both sides' valid requests, for the widener at premain: widening too much is harmless. */
    public static List<Path> folders() {
        Set<Path> folders = new LinkedHashSet<>();
        seen.values().forEach(s -> folders.addAll(s.request().values()));
        return List.copyOf(folders);
    }

    /**
     * Called on entry to the game's main method, before the startup pass lists any mod folder: takes the request file
     * of {@code mainClass}'s side and applies the request premain checked. The other side's file stays. Never throws,
     * logs nothing and touches no game class.
     */
    public static synchronized void preLaunch(String mainClass) {
        if (sideKnown) {
            return;
        }
        sideKnown = true;
        GameSide side = GameSide.ofMainClass(mainClass);
        Path dir = zabricDir;
        if (side == null || dir == null) {
            if (!seen.isEmpty()) {
                discarded = "no request read: " + (side == null ? mainClass + " is neither the client's nor the"
                        + " server's main class" : "Zabric did not read the request files at game start");
            }
            return;
        }
        Path file = OverrideRequest.file(dir, side);
        String why = "discarded the request in " + file + ", so nothing is applied: ";
        byte[] now;
        try {
            now = OverrideRequest.take(file);
        } catch (Throwable t) {
            discarded = why + t;
            discardedError = t;
            return;
        }
        Seen at = seen.get(side);
        if (at == null) {
            if (now != null) {
                discarded = why + "it was written after game start, so the mods in its folders were not widened";
            }
            return;
        }
        if (at.problem() != null) {
            discarded = why + at.problem();
            discardedError = at.error();
            return;
        }
        if (now == null || !Arrays.equals(now, at.content())) {
            discarded = why + "it changed between game start and the game's main method";
            return;
        }
        request = at.request();
        ItemFoldersHook.activate(request);
    }

    /** The requested items the game listed, with their folders. Throws when the override failed this launch. */
    public static synchronized Map<String, Path> current() {
        if (failure != null) {
            throw new IllegalStateException("the launch override failed this launch: " + failure.getMessage(),
                    failure);
        }
        Map<String, Path> applied = new TreeMap<>();
        request.forEach((id, folder) -> {
            if (listed.contains(id)) {
                applied.put(id, folder);
            }
        });
        return Collections.unmodifiableMap(applied);
    }

    /** Called by Zabric's Main: what premain found and the hook did, once the game's log is up. */
    public static synchronized void report() {
        if (reported) {
            return;
        }
        reported = true;
        if (!sideKnown && !seen.isEmpty()) {
            DebugType.Mod.error(PREFIX + "launch override: no request applied: Zabric's preLaunch hook did not run, so"
                    + " it could not tell a client from a dedicated server; the request files stay: %s",
                    seen.values().stream().map(Seen::file).toList());
        }
        if (discarded != null) {
            if (discardedError != null) {
                DebugType.Mod.printException(discardedError, PREFIX + "launch override: " + discarded,
                        LogSeverity.Error);
            } else {
                DebugType.Mod.error(PREFIX + "launch override: %s", discarded);
            }
        }
        if (request.isEmpty()) {
            return;
        }
        if (failure == null && ItemFoldersHook.calls() == 0) {
            // the mod list cannot have loaded without the folder list, so the hook is not in the game's method
            failure = new IllegalStateException(TARGET + "." + ItemFoldersHook.METHOD
                    + " did not run before the mod list loaded");
        }
        if (failure != null) {
            logFailure("not applied: ");
            return;
        }
        request.forEach((id, folder) -> {
            if (listed.contains(id)) {
                logApplied(id, folder);
            } else {
                DebugType.Mod.error(PREFIX + "launch override: Workshop item %s is not among the installed items the"
                        + " game listed (not subscribed, or Steam is off), so %s is not used", id, folder);
            }
        });
    }

    /** The hook replaced the folder of item {@code id}. */
    static synchronized void listed(String id) {
        if (listed.add(id) && reported) {
            logApplied(id, request.get(id));
        }
    }

    /** Whether the override stopped: it is not applied for the rest of the launch. */
    static synchronized boolean failed() {
        return failure != null;
    }

    /** Stops the override for the rest of the launch; reported once. */
    static synchronized void fail(Throwable t) {
        if (failure != null) {
            return;
        }
        failure = t;
        if (reported) {
            logFailure("stopped for the rest of this launch: ");
        }
    }

    private static void logApplied(String id, Path folder) {
        DebugType.Mod.println(PREFIX + "launch override: Workshop item %s uses %s this launch", id, folder);
    }

    private static void logFailure(String what) {
        DebugType.Mod.printException(failure, PREFIX + "launch override: " + what + failure.getMessage(),
                LogSeverity.Error);
    }

    // tests start from a launch without a request
    static synchronized void reset() {
        zabricDir = null;
        seen = Map.of();
        sideKnown = false;
        request = Map.of();
        ItemFoldersHook.activate(Map.of());
        discarded = null;
        discardedError = null;
        listed.clear();
        failure = null;
        reported = false;
    }

    static String discarded() {
        return discarded;
    }

    static Map<String, Path> request() {
        return request;
    }
}
