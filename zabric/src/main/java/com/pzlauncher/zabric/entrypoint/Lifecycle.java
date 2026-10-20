package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.entrypoint.Entrypoints.Declared;
import com.pzlauncher.zabric.hook.impl.HookRuntime;
import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.mods.impl.JarHistory;
import zombie.core.Core;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * Zabric's entrypoint phases. A mod names its entrypoint classes in its jar manifest ({@link #PRE_LAUNCH},
 * {@link #MAIN}); Zabric calls each in its phase whatever the mods' places in the mod list and the preload list, and
 * keeps a mod with a preLaunch entrypoint preloaded. The mod id is the one the loader recorded for the jar, so a jar
 * cannot speak for another mod. Jars without these attributes are left alone. Order within a phase is undefined.
 * <p>
 * A pass's hooks open before its main entrypoints run, so those see their gates' state ({@link HookRuntime}).
 */
public final class Lifecycle {
    public static final String PRE_LAUNCH = "Zabric-PreLaunch";
    public static final String MAIN = "Zabric-Main";

    /** A preLaunch entrypoint called, or a preloaded jar whose manifest could not be read ({@code className} null). */
    private record Called(String modId, String className, Throwable error) {
    }

    // premain and preLaunch cannot log; the main phase reports what they found
    private static boolean installed;
    private static Instrumentation instrumentation;
    private static String failure;
    private static Throwable failureError;
    private static boolean preLaunchRan;
    private static final List<Called> preLaunchCalls = new ArrayList<>();
    private static final Set<String> preLaunched = new HashSet<>();

    private static boolean zabricLoaded;
    private static boolean zabricPreloaded;
    private static boolean started;
    private static boolean stopped;
    private static final Set<String> registered = new HashSet<>();
    private static final Set<String> mainCalled = new HashSet<>();

    private Lifecycle() {
    }

    /** Called by Zabric's PreMain. Never throws, logs nothing and touches no game class. */
    public static synchronized void install() {
        String hook = "its hooks";
        try {
            Instrumentation inst = Loader.instrumentation();
            instrumentation = inst;
            hook = "its preLaunch hook (on the main method of " + String.join(" or ", PreLaunchHook.TARGETS) + ")";
            PreLaunchHook.install(inst);
            hook = "its main hook (on " + Loader.name() + "'s pass over the mod list)";
            Loader.onModsLoaded(inst, Lifecycle::modsLoaded);
            installed = true;
        } catch (Throwable t) {
            failure = "cannot install " + hook + ": " + t;
            failureError = t;
        }
    }

    /** Called on entry to the game's main method. Never throws into it, logs nothing and touches no game class. */
    public static synchronized void preLaunch() {
        if (preLaunchRan) {
            return;
        }
        preLaunchRan = true;
        if (!installed) {
            return;
        }
        try {
            List<Loader.ModJar> preloaded = Loader.preloadedJars();
            JarHistory.preloaded(preloaded);
            for (Loader.ModJar jar : preloaded) {
                Declared declared;
                try {
                    declared = Entrypoints.declared(jar.jar());
                } catch (Throwable t) {
                    preLaunchCalls.add(new Called(jar.modId(), null, t));
                    continue;
                }
                if (declared.preLaunch() != null) {
                    preLaunched.add(jar.modId());
                    preLaunchCalls.add(new Called(jar.modId(), declared.preLaunch(),
                            Entrypoints.call(declared.preLaunch(), PreLaunchEntrypoint.class,
                                    PreLaunchEntrypoint::onPreLaunch)));
                }
            }
        } catch (Throwable t) {
            failure = "the preLaunch phase failed: " + t;
            failureError = t;
        }
    }

    /**
     * Called by Zabric's Main, inside the loader's pass that loads Zabric. On a launch where Zabric did not run at game
     * start there are no hooks: it registers itself and every mod of this pass with a preLaunch entrypoint, so that one
     * restart covers all of them, and runs no entrypoints.
     */
    public static synchronized void onZabricMain(boolean preloaded) {
        zabricLoaded = true;
        zabricPreloaded = preloaded;
        Preload.register("Zabric", preloaded);
        try {
            if (preloaded) {
                start();
                return;
            }
            started = true;
            byMod(Loader.loadedThisPass(Loader.currentPass())).forEach((modId, jars) -> {
                if (Entrypoints.declared(modId, jars).preLaunch() != null && registered.add(modId)) {
                    Preload.register(modId, false);
                }
            });
            // only after the registrations, so that stop() still reports their failure
            stopped = true;
            DebugType.Mod.warn(PREFIX + "entrypoints: not run this launch: Zabric did not run at game start; they run"
                    + " from the next launch on");
        } catch (Throwable t) {
            stop("Zabric's Main failed: " + t, t);
        }
    }

    // the hook's callback, after each of the loader's passes over the mod list
    private static synchronized void modsLoaded(List<String> pass) {
        try {
            Throwable escaped = Loader.modsLoadedEscaped();
            if (escaped != null) {
                // what escaped may be stop() itself, after it marked the session stopped
                stopped = true;
                DebugType.Mod.printException(escaped, PREFIX + "entrypoints: stopped for this session: an earlier main"
                        + " phase failed: " + escaped, LogSeverity.Error);
            }
            start();
            if (stopped) {
                HookRuntime.closeAll();
                return;
            }
            if (!zabricLoaded) {
                // the player denied Zabric, or the loader skipped it: its preloaded hooks must not act for it
                stopped = true;
                HookRuntime.closeAll();
                DebugType.Mod.warn(PREFIX + "entrypoints: not run this session: %s did not approve and load Zabric",
                        Loader.name());
                return;
            }
            List<Loader.ModJar> jars = Loader.loadedThisPass(pass);
            JarHistory.passLoaded(jars);
            Map<String, List<Path>> loaded = byMod(jars);
            HookRuntime.passProcessed(loaded, ClassLoader.getSystemClassLoader(), instrumentation);
            loaded.forEach(Lifecycle::load);
        } catch (Throwable t) {
            stop("the main phase failed: " + t, t);
        }
    }

    /**
     * Throws, naming {@code call}, unless Zabric's services for mods are available: from Zabric's Main on, on a launch
     * where Zabric ran at game start, until its entrypoints stop for the session.
     */
    public static synchronized void requireRunning(String call) {
        String why = !zabricLoaded ? "Zabric's Main has not run yet"
                : !zabricPreloaded ? "Zabric did not run at game start this launch; restart the game once"
                : stopped ? "Zabric's entrypoints are stopped for this session (see the log)" : null;
        if (why != null) {
            throw new IllegalStateException(call + " is not available: " + why);
        }
    }

    private static void load(String modId, List<Path> jars) {
        Declared declared = Entrypoints.declared(modId, jars);
        if (declared.preLaunch() != null && registered.add(modId)) {
            Preload.register(modId, preLaunched.contains(modId));
        }
        if (declared.main() != null && mainCalled.add(modId)) {
            DebugType.Mod.println(PREFIX + "main: %s", modId);
            Throwable t = Entrypoints.call(declared.main(), MainEntrypoint.class, MainEntrypoint::onMain);
            if (t != null) {
                failed(t, "main", modId, declared.main());
            }
        }
    }

    // what premain and preLaunch found, once the game's log is up
    private static void start() {
        if (started) {
            return;
        }
        started = true;
        if (failure == null && PreLaunchHook.problem() != null) {
            failure = "its preLaunch hook failed: " + PreLaunchHook.problem();
            failureError = PreLaunchHook.error();
        }
        if (failure == null && !preLaunchRan) {
            failure = "its preLaunch hook (on the main method of " + String.join(" or ", PreLaunchHook.TARGETS)
                    + ") did not run before the mod list loaded";
        }
        if (failure != null) {
            stop(failure, failureError);
            return;
        }
        for (Called c : preLaunchCalls) {
            if (c.className() == null) {
                DebugType.Mod.printException(c.error(), PREFIX + "cannot read the entrypoints of " + c.modId(),
                        LogSeverity.Error);
                continue;
            }
            DebugType.Mod.println(PREFIX + "preLaunch: %s", c.modId());
            if (c.error() != null) {
                failed(c.error(), "preLaunch", c.modId(), c.className());
            }
        }
    }

    // no partial lifecycle: after this no entrypoint runs this session
    private static void stop(String why, Throwable error) {
        if (stopped) {
            return;
        }
        stopped = true;
        HookRuntime.closeAll();
        String versions = Loader.name() + " " + Loader.version() + ", game " + gameVersion();
        if (error != null) {
            DebugType.Mod.printException(error, PREFIX + "entrypoints: stopped for this session: " + why + " ("
                    + versions + ")", LogSeverity.Error);
        } else {
            DebugType.Mod.error(PREFIX + "entrypoints: stopped for this session: %s (%s)", why, versions);
        }
    }

    private static void failed(Throwable t, String phase, String modId, String className) {
        DebugType.Mod.printException(t, PREFIX + phase + " entrypoint of " + modId + " (" + className + ") failed",
                LogSeverity.Error);
    }

    private static Map<String, List<Path>> byMod(List<Loader.ModJar> jars) {
        Map<String, List<Path>> byMod = new LinkedHashMap<>();
        jars.forEach(j -> byMod.computeIfAbsent(j.modId(), k -> new ArrayList<>()).add(j.jar()));
        return byMod;
    }

    private static String gameVersion() {
        try {
            return Core.getInstance().getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }
}
