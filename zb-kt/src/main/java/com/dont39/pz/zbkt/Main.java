package com.dont39.pz.zbkt;

import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.util.Properties;

/**
 * Called by ZombieBuddy when it reaches this jar in its pass over the mod list, before any mod that requires this one,
 * so a ZombieBuddy Main on purpose: when the mod was not preloaded, it is the last point before those mods' classes
 * load. Java only, no reference to any kotlin.* type: it runs before the swap (checkBootstrapClasses).
 */
public final class Main {
    private static final String PREFIX = "[ZabricKotlin] ";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            installAndReport();
        } catch (Throwable t) {
            DebugType.Mod.printException(t, PREFIX + "not ready: unexpected error: " + t, LogSeverity.Error);
        }
    }

    private static void installAndReport() {
        String version = Main.class.getPackage().getImplementationVersion();
        DebugType.Mod.println(PREFIX + "%s loaded", version != null ? version : "dev");
        Properties runtime;
        try {
            runtime = Install.runtime();
        } catch (KotlinSwap.Failure f) {
            notReady(f.getMessage(), f.getCause());
            return;
        } catch (Throwable t) {
            notReady("unexpected error while installing Kotlin" + (Install.ranAtGameStart() ? " at game start" : "")
                    + ": " + t, t);
            return;
        }
        Throwable check = Install.selfCheck();
        if (check != null) {
            notReady("self-check failed: " + check, check);
            return;
        }
        String when = Install.ranAtGameStart() ? "installed at game start"
                : "installed when the mod list loaded, not at game start; restart once so that it installs at game start";
        DebugType.Mod.println(PREFIX + "ready: Kotlin %s, kotlinx-coroutines %s, kotlinx-serialization %s; %s",
                runtime.getProperty("version"), runtime.getProperty("coroutines"), runtime.getProperty("serialization"),
                when);
    }

    private static void notReady(String why, Throwable error) {
        if (error != null) {
            DebugType.Mod.printException(error, PREFIX + "not ready: " + why, LogSeverity.Error);
        } else {
            DebugType.Mod.error(PREFIX + "not ready: %s", why);
        }
    }
}
