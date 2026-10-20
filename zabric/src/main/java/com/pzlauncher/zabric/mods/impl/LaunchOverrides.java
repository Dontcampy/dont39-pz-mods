package com.pzlauncher.zabric.mods.impl;

import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.internal.GameSide;
import com.pzlauncher.zabric.loader.Loader;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * This launch's override of Workshop item folders: read from the request file at premain, applied by
 * {@link ItemFoldersHook} to every pass over the mod list, reported at MAIN.
 */
public final class LaunchOverrides {
    static final String TARGET = "zombie.ZomboidFileSystem";

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
     * Called by Zabric's PreMain before the widener scans, which reads the requested folders' lists. Never throws,
     * logs nothing and touches no game class.
     */
    public static void read() {
        try {
            read(GameSide.command(), GameFolders.zabricDir(), GameFolders.workshopContent());
        } catch (Throwable t) {
            request = Map.of();
            discarded = "no request read: " + t;
            discardedError = t;
        }
    }

    static void read(String command, Path zabricDir, List<Path> workshopContent) {
        GameSide side = GameSide.ofCommand(command);
        if (side == null) {
            // without a request file there is nothing this launch could get wrong
            if (Arrays.stream(GameSide.values()).anyMatch(s -> Files.exists(OverrideRequest.file(zabricDir, s)))) {
                // reading the other side's request would apply it to the wrong process
                discarded = "no request read: cannot tell a client from a dedicated server by the main class of '"
                        + command + "' (expected " + GameSide.CLIENT.mainClass + " or " + GameSide.SERVER.mainClass
                        + ")";
            }
            request = Map.of();
            return;
        }
        Path file = OverrideRequest.file(zabricDir, side);
        try {
            request = OverrideRequest.consume(file, workshopContent);
        } catch (OverrideRequest.Invalid e) {
            request = Map.of();
            discarded = "discarded the request in " + file + ", so nothing is applied: " + e.getMessage();
        } catch (Throwable t) {
            request = Map.of();
            discarded = "discarded the request in " + file + ", so nothing is applied: " + t;
            discardedError = t;
        }
    }

    /** Called by Zabric's PreMain; installs the hook only when there is a request. Never throws and logs nothing. */
    public static void install() {
        install(Loader::instrumentation, TARGET);
    }

    static void install(Supplier<Instrumentation> instrumentation, String target) {
        Map<String, Path> r = request;
        if (r.isEmpty()) {
            return;
        }
        try {
            ItemFoldersHook.install(instrumentation.get(), target, r);
        } catch (Throwable t) {
            fail(new IllegalStateException("cannot install its hook on " + target + "." + ItemFoldersHook.METHOD
                    + ": " + t, t));
        }
    }

    /** The folders of this launch's request, valid or none; empty when premain did not read one. */
    public static List<Path> folders() {
        return List.copyOf(request.values());
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
        request = Map.of();
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
