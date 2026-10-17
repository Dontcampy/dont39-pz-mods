package com.dont39.pz.zbskiponlinechecks;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Loader;

import java.util.List;

/**
 * Called by ZombieBuddy at game start: from its patches_jar option (agent start), and when it preloads the mod. Logs
 * nothing: console.txt starts only once ZombieBuddy hooks the game log, so the main entrypoint reports.
 */
public final class PreMain {
    private PreMain() {
    }

    public static void premain(String[] args) {
        try {
            // the preload call comes after ZombieBuddy's own game-start requests; Zabric's preLaunch covers that way
            if (fromPatchesJar()) {
                Installer.install(Installer.Way.AGENT_START);
            }
        } catch (RuntimeException e) {
            // ZombieBuddy reports it on stderr, which nothing keeps; preLaunch rethrows it for Zabric to log
            Installer.fail(e);
            throw e;
        }
    }

    private static boolean fromPatchesJar() {
        List<StackWalker.StackFrame> frames = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(s -> s.filter(f -> f.getDeclaringClass().getPackageName()
                        .equals(Loader.class.getPackageName())).toList());
        for (int i = 0; i + 1 < frames.size(); i++) {
            if (is(frames.get(i), Loader.class, "loadJar")) {
                StackWalker.StackFrame caller = frames.get(i + 1);
                if (is(caller, Agent.class, "premain")) {
                    return true;
                }
                if (is(caller, Loader.class, "preloadMods")) {
                    return false;
                }
                break;
            }
        }
        throw new IllegalStateException("ZombieBuddy " + Installer.zbVersion() + " called PreMain from neither its"
                + " patches_jar option nor its preload: " + frames);
    }

    private static boolean is(StackWalker.StackFrame frame, Class<?> type, String method) {
        return frame.getDeclaringClass() == type && frame.getMethodName().equals(method);
    }
}
