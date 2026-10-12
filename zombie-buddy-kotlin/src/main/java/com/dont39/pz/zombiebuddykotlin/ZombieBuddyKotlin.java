package com.dont39.pz.zombiebuddykotlin;

import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * The game bundles kotlin-stdlib 1.4.10 in projectzomboid.jar, which wins over any stdlib in a mod jar. This puts the
 * stdlib shipped next to the mod jar on the bootstrap search before anything loads a kotlin.* class, so every mod sees
 * the current Kotlin; kotlinx comes from the mod jar itself.
 * <p>
 * Java only, with no reference to any kotlin.* type: this runs before the swap (checkBootstrapClasses).
 */
public final class ZombieBuddyKotlin {
    private static final String PREFIX = "[ZombieBuddyKotlin] ";
    private static final String REINSTALL = "reinstall ZombieBuddyKotlin (unsubscribe and subscribe again)";
    private static final String SELF_CHECK = "com.dont39.pz.zombiebuddykotlin.selfcheck.SelfCheck";
    private static volatile boolean ready;
    private static volatile String kotlinVersion;

    private ZombieBuddyKotlin() {
    }

    /**
     * The installed Kotlin version; null when not ready.
     */
    public static String kotlinVersion() {
        return ready ? kotlinVersion : null;
    }

    static void install() {
        Properties runtime = null;
        String why;
        Throwable error = null;
        try {
            runtime = runtime();
            why = runtime == null ? "stdlib.properties is missing from ZombieBuddyKotlin.jar; " + REINSTALL
                    : swap(runtime);
        } catch (Throwable t) {
            why = "unexpected error while installing Kotlin: " + t;
            error = t;
        }
        if (why == null) {
            try {
                Class.forName(SELF_CHECK, true, ZombieBuddyKotlin.class.getClassLoader()).getMethod("run")
                        .invoke(null);
            } catch (Throwable t) {
                error = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                why = "self-check failed: " + error;
            }
        }
        if (why != null) {
            if (error != null) {
                DebugType.Mod.printException(error, PREFIX + "not ready: " + why, LogSeverity.Error);
            } else {
                warn("not ready: " + why);
            }
            return;
        }
        kotlinVersion = runtime.getProperty("version");
        ready = true;
        log("ready: Kotlin " + kotlinVersion + ", kotlinx-coroutines " + runtime.getProperty("coroutines")
                + ", kotlinx-serialization " + runtime.getProperty("serialization"));
    }

    /**
     * Appends the stdlib to the bootstrap search; returns why it could not, or null.
     */
    private static String swap(Properties runtime) throws Exception {
        Instrumentation inst = instrumentation();
        if (inst == null) {
            return "ZombieBuddy " + zombieBuddyVersion() + " does not expose its Instrumentation; check for a"
                    + " ZombieBuddyKotlin update that supports this ZombieBuddy";
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith("kotlin.")) {
                return "Kotlin was already loaded (" + c.getName() + ") by another mod or a mod loaded before"
                        + " ZombieBuddyKotlin; load ZombieBuddyKotlin right after ZombieBuddy";
            }
        }

        String file = runtime.getProperty("file");
        CodeSource source = ZombieBuddyKotlin.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return "cannot tell where ZombieBuddyKotlin.jar is, so " + file + " cannot be found";
        }
        Path stdlib = Path.of(source.getLocation().toURI()).resolveSibling("lib").resolve(file);
        if (!Files.isRegularFile(stdlib)) {
            return stdlib + " is missing; " + REINSTALL;
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(stdlib)));
        if (!sha256.equalsIgnoreCase(runtime.getProperty("sha256"))) {
            return stdlib + " does not match its checksum (damaged or modified); " + REINSTALL;
        }

        inst.appendToBootstrapClassLoaderSearch(new JarFile(stdlib.toFile()));

        String expected = runtime.getProperty("version");
        String actual = String.valueOf(Class.forName("kotlin.KotlinVersion").getField("CURRENT").get(null));
        if (!actual.equals(expected)) {
            return "Kotlin " + actual + " was loaded instead of " + expected;
        }
        return null;
    }

    private static Properties runtime() throws Exception {
        try (InputStream in = ZombieBuddyKotlin.class.getResourceAsStream("stdlib.properties")) {
            if (in == null) {
                return null;
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        }
    }

    /**
     * ZombieBuddy keeps it package-private; null if it is gone or not set.
     */
    private static Instrumentation instrumentation() {
        try {
            Field f = Class.forName("me.zed_0xff.zombie_buddy.Loader").getDeclaredField("g_instrumentation");
            f.setAccessible(true);
            return (Instrumentation) f.get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String zombieBuddyVersion() {
        try {
            return me.zed_0xff.zombie_buddy.ZombieBuddy.getVersion();
        } catch (RuntimeException | LinkageError e) {
            return "(unknown version)";
        }
    }

    static void log(String message) {
        DebugType.Mod.println(PREFIX + message);
    }

    static void warn(String message) {
        DebugType.Mod.warn(PREFIX + message);
    }
}
