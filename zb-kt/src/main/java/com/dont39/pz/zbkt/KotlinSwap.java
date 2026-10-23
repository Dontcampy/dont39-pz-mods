package com.dont39.pz.zbkt;

import com.pzlauncher.zabric.loader.Loader;

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
 * Installs the current Kotlin. The game bundles kotlin-stdlib 1.4.10 in projectzomboid.jar, which wins over any stdlib
 * in a mod jar; this puts the stdlib shipped next to the mod jar on the bootstrap search first, so every mod sees the
 * current Kotlin. kotlinx comes from the mod jar. Works only while no kotlin.* class is loaded.
 */
final class KotlinSwap {
    private static final String REINSTALL = "reinstall ZabricKotlin (unsubscribe and subscribe again)";

    private KotlinSwap() {
    }

    /** The swap failed for a reason a player can act on. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }

        Failure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Returns the shipped versions (stdlib.properties). {@code atGameStart}: called from {@link PreMain}. */
    static Properties install(boolean atGameStart) throws Exception {
        Properties p = new Properties();
        try (InputStream in = KotlinSwap.class.getResourceAsStream("stdlib.properties")) {
            if (in == null) {
                throw new Failure("stdlib.properties is missing from ZabricKotlin.jar; " + REINSTALL);
            }
            p.load(in);
        }

        Instrumentation inst;
        try {
            inst = Loader.instrumentation();
        } catch (NoClassDefFoundError e) {
            // Zabric's jar is not on the classpath yet: ZombieBuddy has not loaded Zabric before this jar
            throw new Failure("Zabric's Java code is not loaded; enable Zabric, approve it in ZombieBuddy and keep it"
                    + " above ZabricKotlin in the mod list", e);
        } catch (IllegalStateException e) {
            throw new Failure(e.getMessage() + "; check for a Zabric update that supports this ZombieBuddy", e);
        }
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().startsWith("kotlin.")) {
                throw new Failure("Kotlin was already loaded (" + c.getName() + ") before ZabricKotlin could"
                        + " install the current one, " + (atGameStart
                        ? "at game start: by a preload mod that ZombieBuddy runs before ZabricKotlin, or by one that"
                                + " uses Kotlin without requiring ZabricKotlin"
                        : "by ZombieBuddy, a preload mod that uses Kotlin, or a mod above ZabricKotlin in the mod"
                                + " list"));
            }
        }

        String file = p.getProperty("file");
        CodeSource source = KotlinSwap.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            throw new Failure("cannot tell where ZabricKotlin.jar is, so " + file + " cannot be found");
        }
        Path stdlib = Path.of(source.getLocation().toURI()).resolveSibling("lib").resolve(file);
        if (!Files.isRegularFile(stdlib)) {
            throw new Failure(stdlib + " is missing; " + REINSTALL);
        }
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(stdlib)));
        if (!sha256.equalsIgnoreCase(p.getProperty("sha256"))) {
            throw new Failure(stdlib + " does not match its checksum (damaged or modified); " + REINSTALL);
        }

        inst.appendToBootstrapClassLoaderSearch(new JarFile(stdlib.toFile()));

        String expected = p.getProperty("version");
        String actual = String.valueOf(Class.forName("kotlin.KotlinVersion").getField("CURRENT").get(null));
        if (!actual.equals(expected)) {
            throw new Failure("Kotlin " + actual + " was loaded instead of " + expected);
        }
        return p;
    }
}
