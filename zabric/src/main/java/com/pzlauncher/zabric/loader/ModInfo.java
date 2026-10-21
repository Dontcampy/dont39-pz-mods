package com.pzlauncher.zabric.loader;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.internal.Folders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * ZombieBuddy's reading of mod folders ({@code JavaModInfo}, {@code Loader.loadMods}), re-implemented: its parser is
 * private. Plain file IO, as premain uses it.
 */
final class ModInfo {
    private static final String FILE = "mod.info";
    private static final String COMMON = "common";
    private static final String SERVER_ONLY = "media/java/server/";
    private static final String ZB_PRELOAD = "ZB-Preload";

    private ModInfo() {
    }

    /** One {@code mod.info}'s lines. */
    private record Values(List<String> lines) {
        String jar() {
            return first("javajarfile=", v -> v.endsWith(".jar"));
        }

        String pkg() {
            return first("javapkgname=", v -> true);
        }

        String name() {
            return first("name=", v -> true);
        }

        boolean preload() {
            String last = null;
            for (String line : lines) {
                if (key(line, "javapreload=")) {
                    last = line;
                }
            }
            return last != null && value(last).equalsIgnoreCase("true");
        }

        // the game's own reading: the last id= line wins
        String id() {
            String last = null;
            for (String line : lines) {
                if (line.trim().startsWith("id=")) {
                    last = line.trim();
                }
            }
            if (last == null) {
                return null;
            }
            String id = last.substring("id=".length()).trim();
            return id.isEmpty() ? null : id;
        }

        // wider than the game's reading: for a refusal, matching too much is harmless
        List<String> anyIds() {
            List<String> ids = new ArrayList<>();
            for (String line : lines) {
                int eq = line.indexOf('=');
                if (eq > 0 && line.substring(0, eq).strip().equalsIgnoreCase("id")) {
                    ids.add(line.substring(eq + 1).strip());
                }
            }
            return ids;
        }

        private String first(String key, Predicate<String> valid) {
            for (String line : lines) {
                if (key(line, key)) {
                    String v = value(line);
                    if (!v.isEmpty() && valid.test(v)) {
                        return v;
                    }
                }
            }
            return null;
        }

        private static boolean key(String line, String key) {
            return line.indexOf('=') >= 0 && line.toLowerCase(Locale.ROOT).startsWith(key);
        }

        private static String value(String line) {
            return line.substring(line.indexOf('=') + 1).trim();
        }
    }

    static List<Loader.JavaMod> javaMods(Path modsFolder, Function<Path, String> versionFolder) throws IOException {
        if (!Files.isDirectory(modsFolder)) {
            return List.of();
        }
        List<Loader.JavaMod> mods = new ArrayList<>();
        for (Path mod : Folders.children(modsFolder)) {
            Path version = mod.resolve(versionFolder.apply(mod));
            Path common = mod.resolve(COMMON);
            Loader.JavaMod found = parse(mod, version, version);
            if (found == null) {
                found = parse(mod, common, common);
            }
            if (found == null && Files.isDirectory(version)) {
                found = parse(mod, common, version);
            }
            if (found != null) {
                mods.add(found);
            }
        }
        return mods;
    }

    static Map<Path, Set<String>> modIds(Path modsFolder) throws IOException {
        Map<Path, Set<String>> mods = new LinkedHashMap<>();
        for (Path mod : Folders.children(modsFolder)) {
            Set<String> ids = new LinkedHashSet<>();
            boolean found = false;
            for (Path version : Folders.children(mod)) {
                Path inf = version.resolve(FILE);
                if (Files.isRegularFile(inf)) {
                    found = true;
                    ids.addAll(read(inf).anyIds());
                }
            }
            if (found) {
                mods.put(mod, ids);
            }
        }
        return mods;
    }

    private static Loader.JavaMod parse(Path mod, Path infDir, Path jarDir) throws IOException {
        Path inf = infDir.resolve(FILE);
        if (!Files.isDirectory(infDir) || !Files.isRegularFile(inf)) {
            return null;
        }
        Values values = read(inf);
        String jarName = values.jar();
        String pkg = values.pkg();
        if (jarName == null || pkg == null || jarName.contains(SERVER_ONLY)) {
            return null;
        }
        Path jar = jarDir.resolve(jarName);
        if (!Files.isRegularFile(jar)) {
            return null;
        }
        String id = values.id();
        String name = values.name() != null ? values.name() : id != null ? id : mod.getFileName().toString();
        return new Loader.JavaMod(mod, id, name, jar, pkg, values.preload() || preloadManifest(jar));
    }

    private static Values read(Path inf) throws IOException {
        try {
            // UTF-8 as ZombieBuddy reads it; a malformed byte does not fail the read
            return new Values(new String(Files.readAllBytes(inf), StandardCharsets.UTF_8).lines().toList());
        } catch (IOException e) {
            throw new IOException("cannot read " + inf + ": " + e.getMessage(), e);
        }
    }

    // Zabric preloads a mod with a preLaunch entrypoint itself (entrypoint/Preload.java)
    private static boolean preloadManifest(Path jar) throws IOException {
        Attributes attributes;
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            attributes = manifest == null ? null : manifest.getMainAttributes();
        } catch (IOException e) {
            throw new IOException("cannot read the manifest of " + jar + ": " + e.getMessage(), e);
        }
        if (attributes == null) {
            return false;
        }
        return "true".equalsIgnoreCase(attributes.getValue(ZB_PRELOAD))
                || attributes.getValue(Lifecycle.PRE_LAUNCH) != null;
    }
}
