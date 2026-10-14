package com.pzlauncher.zabric.hook.impl;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.loader.Loader;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zabric's side of the hook runtime: one {@link ModState} per mod id, opened and closed by Zabric's lifecycle after
 * each of the loader's passes over the mod list. Not API: only Zabric calls it.
 */
public final class HookRuntime {
    static final String PREFIX = "[Zabric] ";

    private static final Map<String, ModState> mods = new ConcurrentHashMap<>();
    private static final WeaveCheck weave = new WeaveCheck();
    private static volatile Log log = new GameLog();

    private HookRuntime() {
    }

    /** Where the hook runtime logs; the game's log unless Zabric's tests replace it. */
    public interface Log {
        void info(String format, Object... args);

        void error(String format, Object... args);

        void exception(Throwable t, String message);
    }

    public static ModHooks mod(String modId) {
        return state(modId);
    }

    private static ModState state(String modId) {
        return mods.computeIfAbsent(modId, ModState::new);
    }

    /**
     * After a pass over the mod list: closes the hooks of mods not in {@code jarsByMod} (the jars the loader loaded for
     * each mod) and opens those of the mods in it. The first time a mod is in a pass, the targets of its default-gate
     * hook classes are checked against {@code loader}; a mismatch stops its hooks for the session. Then checks through
     * {@code inst} that the loader wove the mods' hooks ({@link WeaveCheck}): now for targets already loaded, later for
     * the others as they load. A mod entering the pass opens only after that check. A mod checked for the first time
     * and open after that is logged as ready. Call it after the loader registered the pass's patches.
     */
    public static synchronized void passProcessed(Map<String, List<Path>> jarsByMod, ClassLoader loader,
                                                  Instrumentation inst) {
        mods.forEach((modId, state) -> {
            if (!jarsByMod.containsKey(modId)) {
                state.leavePass();
            }
        });
        Set<ModState> entering = new HashSet<>();
        List<ModState> checked = new ArrayList<>();
        jarsByMod.forEach((modId, jars) -> {
            ModState state = state(modId);
            if (state.firstPass() && check(state, jars, loader)) {
                checked.add(state);
            }
            if (state.enterPass()) {
                entering.add(state);
            }
        });
        List<ModState> live = mods.values().stream().filter(ModState::live).toList();
        try {
            weave.afterPass(inst, loader, live, entering);
        } catch (Throwable t) {
            // unchecked hooks could be missing without anyone noticing
            for (ModState state : live) {
                if (!state.listings().isEmpty() && state.stop()) {
                    log.exception(t, PREFIX + state.modId() + ": hooks stopped: cannot check that " + Loader.name()
                            + " wove them");
                }
            }
        }
        entering.forEach(ModState::release);
        // a weave failure reported to a named gate leaves the mod open: that gate's owner reports it
        for (ModState state : checked) {
            int count = state.listings().size();
            if (state.open() && count > 0) {
                log.info(PREFIX + "%s: hooks ready (%d hook %s)", state.modId(), count,
                        count == 1 ? "class" : "classes");
            }
        }
    }

    /** Closes every mod's hooks, e.g. when Zabric's lifecycle stops. */
    public static synchronized void closeAll() {
        mods.values().forEach(ModState::leavePass);
        weave.clear();
    }

    /** For Zabric's tests. */
    public static void logTo(Log log) {
        HookRuntime.log = log;
    }

    static Log log() {
        return log;
    }

    /** False when the targets do not match, which stops the mod's hooks. */
    private static boolean check(ModState state, List<Path> jars, ClassLoader loader) {
        String modId = state.modId();
        List<HookListing> mine = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        try {
            for (Path jar : jars) {
                List<HookListing> listings;
                try {
                    listings = HookListing.readAll(jar);
                } catch (IOException | RuntimeException e) {
                    problems.add("cannot read the hook listings in " + jar + ": " + e);
                    continue;
                }
                for (HookListing listing : listings) {
                    mine.add(listing);
                    if (!listing.modId().equals(modId)) {
                        problems.add(listing.source() + " names the mod " + listing.modId() + ", but " + Loader.name()
                                + " loaded the jar for " + modId);
                    } else if (listing.defaultGate()) {
                        String problem = listing.problem(loader);
                        if (problem != null) {
                            problems.add(problem);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            state.stop();
            log.exception(t, PREFIX + modId + ": hooks stopped: checking their targets failed");
            return false;
        }
        if (!problems.isEmpty()) {
            state.stop();
            log.error(PREFIX + "%s: hooks stopped: %s", modId, String.join("; ", problems));
            return false;
        }
        state.listings(mine);
        return true;
    }

    private static final class GameLog implements Log {
        @Override
        public void info(String format, Object... args) {
            DebugType.Mod.println(format, args);
        }

        @Override
        public void error(String format, Object... args) {
            DebugType.Mod.error(format, args);
        }

        @Override
        public void exception(Throwable t, String message) {
            DebugType.Mod.printException(t, message, LogSeverity.Error);
        }
    }
}
