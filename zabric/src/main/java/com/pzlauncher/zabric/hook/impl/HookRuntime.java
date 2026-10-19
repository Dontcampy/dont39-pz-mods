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
import java.util.function.Consumer;
import java.util.function.Function;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

public final class HookRuntime {
    private static final Map<String, ModState> mods = new ConcurrentHashMap<>();
    private static final WeaveCheck weave = new WeaveCheck();
    private static volatile Log log = new GameLog();
    // opened at preLaunch: a target class that never loads is never checked from its bytes; looked up at the first
    // pass, when the jars of the mods it loads are on the class path too
    private static final List<ModState> presenceDue = new ArrayList<>();

    private HookRuntime() {
    }

    /** Replaced by Zabric's tests. */
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
     * At preLaunch, for the mods whose jars the loader preloaded: opens their hooks before the first pass, and keeps
     * the {@code launchWide} ones open on every pass. Their target methods are checked from class bytes: from the
     * class files of targets already loaded, and as each other target loads, since loading the targets here would
     * come before the game loads them. Their target classes are looked up, and the weaving of the loaded ones checked,
     * at the first pass. What the runtime logs from here on is held until {@link #releaseLog()}. Call after the loader
     * registered its premain patch transformers.
     */
    public static synchronized void preLaunch(Map<String, List<Path>> jarsByMod, Set<String> launchWide,
                                              ClassLoader loader, Instrumentation inst) {
        if (!(log instanceof HeldLog)) {
            log = new HeldLog();
        }
        Set<String> loaded = new HashSet<>();
        for (Class<?> c : inst.getAllLoadedClasses()) {
            loaded.add(c.getName());
        }
        Set<ModState> entering = new HashSet<>();
        jarsByMod.forEach((modId, jars) -> {
            ModState state = state(modId);
            if (state.firstPass()
                    && check(state, jars, listing -> TargetCheck.loadedProblem(listing, loaded, loader))) {
                state.openAtPreLaunch(launchWide.contains(modId));
                entering.add(state);
                presenceDue.add(state);
            }
        });
        weaveAndOpen(loader, inst, entering, List.of(), true);
    }

    /** Call after the loader registered the pass's patch transformers: the weave check must come after them. */
    public static synchronized void passProcessed(Map<String, List<Path>> jarsByMod, ClassLoader loader,
                                                  Instrumentation inst) {
        mods.forEach((modId, state) -> {
            if (!jarsByMod.containsKey(modId)) {
                state.leavePass();
            }
        });
        List<ModState> checked = new ArrayList<>();
        presenceDue.forEach(state -> {
            if (checkPresence(state, loader)) {
                checked.add(state);
            }
        });
        presenceDue.clear();
        Set<ModState> entering = new HashSet<>();
        jarsByMod.forEach((modId, jars) -> {
            ModState state = state(modId);
            if (state.firstPass() && check(state, jars, listing -> TargetCheck.problem(listing, loader))) {
                checked.add(state);
            }
            if (state.enterPass()) {
                entering.add(state);
            }
        });
        weaveAndOpen(loader, inst, entering, checked, false);
    }

    // entering: held until the weave check has seen their loaded targets
    private static void weaveAndOpen(ClassLoader loader, Instrumentation inst, Set<ModState> entering,
                                     List<ModState> checked, boolean preLaunch) {
        List<ModState> live = mods.values().stream().filter(ModState::live).toList();
        try {
            if (preLaunch) {
                weave.preLaunch(inst, loader, live);
            } else {
                weave.afterPass(inst, loader, live, entering);
            }
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

    /** Closes every mod's hooks, launch-wide ones too; returns the mods whose hooks were open since preLaunch. */
    public static synchronized List<String> closeAll() {
        List<String> sincePreLaunch = new ArrayList<>();
        mods.values().forEach(state -> {
            if (state.close()) {
                sincePreLaunch.add(state.modId());
            }
        });
        weave.clear();
        presenceDue.clear();
        sincePreLaunch.sort(null);
        return sincePreLaunch;
    }

    public static void logTo(Log log) {
        HookRuntime.log = log;
    }

    /** Once the game's log is up: logs what {@link #preLaunch} held, in order, and logs directly from then on. */
    public static void releaseLog() {
        releaseLog(new GameLog());
    }

    static synchronized void releaseLog(Log to) {
        if (log instanceof HeldLog held) {
            held.release(to);
            log = to;
        }
    }

    static Log log() {
        return log;
    }

    // targets: the problem of a default-gate listing, or null
    private static boolean check(ModState state, List<Path> jars, Function<HookListing, String> targets) {
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
                        String problem = targets.apply(listing);
                        if (problem != null) {
                            problems.add(problem);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            stopChecked(state, t);
            return false;
        }
        if (!problems.isEmpty()) {
            stopMismatched(state, problems);
            return false;
        }
        state.listings(mine);
        return true;
    }

    private static boolean checkPresence(ModState state, ClassLoader loader) {
        List<String> problems = new ArrayList<>();
        try {
            for (HookListing listing : state.listings()) {
                if (listing.defaultGate()) {
                    String problem = TargetCheck.presenceProblem(listing, loader);
                    if (problem != null) {
                        problems.add(problem);
                    }
                }
            }
        } catch (Throwable t) {
            stopChecked(state, t);
            return false;
        }
        if (!problems.isEmpty()) {
            stopMismatched(state, problems);
            return false;
        }
        return true;
    }

    // a mod already stopped by one of its hooks has been reported
    private static void stopChecked(ModState state, Throwable t) {
        if (state.stop()) {
            log.exception(t, PREFIX + state.modId() + ": hooks stopped: checking their targets failed");
        }
    }

    private static void stopMismatched(ModState state, List<String> problems) {
        if (state.stop()) {
            log.error(PREFIX + "%s: hooks stopped: %s", state.modId(), String.join("; ", problems));
        }
    }

    // lines from threads that log while it is released keep their place after the held ones
    private static final class HeldLog implements Log {
        private final List<Consumer<Log>> lines = new ArrayList<>();
        private Log to;

        @Override
        public void info(String format, Object... args) {
            add(l -> l.info(format, args));
        }

        @Override
        public void error(String format, Object... args) {
            add(l -> l.error(format, args));
        }

        @Override
        public void exception(Throwable t, String message) {
            add(l -> l.exception(t, message));
        }

        private synchronized void add(Consumer<Log> line) {
            if (to == null) {
                lines.add(line);
            } else {
                line.accept(to);
            }
        }

        synchronized void release(Log to) {
            this.to = to;
            lines.forEach(line -> line.accept(to));
            lines.clear();
        }
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
