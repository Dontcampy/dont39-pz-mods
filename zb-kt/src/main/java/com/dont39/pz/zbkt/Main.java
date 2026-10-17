package com.dont39.pz.zbkt;

import com.dont39.pz.zbkt.selfcheck.SelfCheck;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.util.Properties;

/**
 * Called by ZombieBuddy when it reaches this jar in its pass over the mod list, before any mod that requires this one,
 * so a ZombieBuddy Main on purpose: it is the last point before those mods' classes load. Java only, no reference to any
 * kotlin.* type: it runs before the swap (checkBootstrapClasses).
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
            runtime = KotlinSwap.install();
        } catch (KotlinSwap.Failure f) {
            notReady(f.getMessage(), f.getCause());
            return;
        } catch (Throwable t) {
            notReady("unexpected error while installing Kotlin: " + t, t);
            return;
        }
        try {
            SelfCheck.run();
        } catch (Throwable t) {
            notReady("self-check failed: " + t, t);
            return;
        }
        DebugType.Mod.println(PREFIX + "ready: Kotlin %s, kotlinx-coroutines %s, kotlinx-serialization %s",
                runtime.getProperty("version"), runtime.getProperty("coroutines"), runtime.getProperty("serialization"));
    }

    private static void notReady(String why, Throwable error) {
        if (error != null) {
            DebugType.Mod.printException(error, PREFIX + "not ready: " + why, LogSeverity.Error);
        } else {
            DebugType.Mod.error(PREFIX + "not ready: %s", why);
        }
    }
}
