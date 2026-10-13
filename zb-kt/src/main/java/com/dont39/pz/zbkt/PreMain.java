package com.dont39.pz.zbkt;

import com.dont39.pz.zabric.agent.ZombieBuddyAgent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Called by ZombieBuddy at game start, before any mod or game code can load a kotlin.* class. The game bundles
 * kotlin-stdlib 1.4.10 in projectzomboid.jar, which wins over any stdlib in a mod jar; this puts the stdlib shipped next
 * to the mod jar on the bootstrap search first, so every mod sees the current Kotlin. kotlinx comes from the mod jar.
 * <p>
 * Java only, no game classes and no logging (the game's log is not up); {@link ZombieBuddyKotlin} reports the outcome.
 */
public final class PreMain {
    private static final String REINSTALL = "reinstall ZombieBuddyKotlin (unsubscribe and subscribe again)";

    private static volatile boolean ran;
    private static volatile Properties runtime;
    private static volatile String problem;
    private static volatile Throwable error;

    private PreMain() {
    }

    /** Whether ZombieBuddy preloaded the mod this launch. */
    public static boolean ran() {
        return ran;
    }

    /** The shipped versions (stdlib.properties); null until premain read them. */
    static Properties runtime() {
        return runtime;
    }

    /** Why the swap failed; null when it worked or premain did not run. */
    static String problem() {
        return problem;
    }

    static Throwable error() {
        return error;
    }

    public static void premain(String[] args) {
        ran = true;
        try {
            problem = swap();
        } catch (Throwable t) {
            error = t;
            problem = "unexpected error while installing Kotlin: " + t;
        }
    }

    private static String swap() throws Exception {
        Properties p = new Properties();
        try (InputStream in = PreMain.class.getResourceAsStream("stdlib.properties")) {
            if (in == null) {
                return "stdlib.properties is missing from ZombieBuddyKotlin.jar; " + REINSTALL;
            }
            p.load(in);
        }
        runtime = p;

        Instrumentation inst;
        try {
            inst = ZombieBuddyAgent.instrumentation();
        } catch (NoClassDefFoundError e) {
            error = e;
            return "Zabric's Java code did not load at game start before ZombieBuddyKotlin; enable Zabric and restart"
                    + " the game once";
        } catch (IllegalStateException e) {
            error = e;
            return e.getMessage() + "; check for a Zabric update that supports this ZombieBuddy";
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith("kotlin.")) {
                return "Kotlin was already loaded (" + c.getName() + ") at game start, before ZombieBuddyKotlin, by"
                        + " ZombieBuddy or another preload mod";
            }
        }

        String file = p.getProperty("file");
        CodeSource source = PreMain.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return "cannot tell where ZombieBuddyKotlin.jar is, so " + file + " cannot be found";
        }
        Path stdlib = Path.of(source.getLocation().toURI()).resolveSibling("lib").resolve(file);
        if (!Files.isRegularFile(stdlib)) {
            return stdlib + " is missing; " + REINSTALL;
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(stdlib)));
        if (!sha256.equalsIgnoreCase(p.getProperty("sha256"))) {
            return stdlib + " does not match its checksum (damaged or modified); " + REINSTALL;
        }

        inst.appendToBootstrapClassLoaderSearch(new JarFile(stdlib.toFile()));

        String expected = p.getProperty("version");
        String actual = String.valueOf(Class.forName("kotlin.KotlinVersion").getField("CURRENT").get(null));
        if (!actual.equals(expected)) {
            return "Kotlin " + actual + " was loaded instead of " + expected;
        }
        return null;
    }
}
