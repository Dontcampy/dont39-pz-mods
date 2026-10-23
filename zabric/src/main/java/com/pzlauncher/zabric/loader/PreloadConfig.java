package com.pzlauncher.zabric.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pzlauncher.zabric.internal.AtomicWrite;
import me.zed_0xff.zombie_buddy.Agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * ZombieBuddy's {@code preload_mods} ({@code config.json}), the only way to get an unsigned mod preloaded: ZombieBuddy's
 * approval dialog offers preload only to validly signed jars. Absolute paths, since ZombieBuddy resolves relative ones
 * against the game folder. ZombieBuddy then runs the mod at premain without asking again, also after an update.
 */
final class PreloadConfig {
    private static final String ZABRIC = "Zabric";

    private PreloadConfig() {
    }

    static Loader.PreloadEntry keep(String modId, Path versionDir, Path commonDir) throws IOException {
        return keep(Agent.configDir().resolve("config.json"), modId, versionDir, commonDir);
    }

    static Loader.PreloadEntry keep(Path config, String modId, Path versionDir, Path commonDir) throws IOException {
        Path inf = versionDir.resolve("mod.info");
        String jarFile = modInfoValue(inf, "javaJarFile");
        if (jarFile == null && commonDir != null) {
            // ZombieBuddy also takes mod.info from common/, with the jar still in the version folder
            inf = commonDir.resolve("mod.info");
            jarFile = modInfoValue(inf, "javaJarFile");
        }
        if (jarFile == null) {
            throw new IllegalStateException("no mod.info of " + modId + " in " + versionDir + " has javaJarFile");
        }
        Path jar = versionDir.resolve(jarFile).normalize();
        checkPreloadable(modId, inf, jar);

        ObjectMapper json = new ObjectMapper();
        ObjectNode root;
        if (Files.exists(config)) {
            JsonNode read = json.readTree(Files.readString(config, StandardCharsets.UTF_8));
            if (!(read instanceof ObjectNode)) {
                throw new IllegalStateException(config + " is not a JSON object; not touching it");
            }
            root = (ObjectNode) read;
        } else {
            root = json.createObjectNode();
        }
        JsonNode mods = root.get("preload_mods");
        if (mods != null && !(mods instanceof ObjectNode)) {
            throw new IllegalStateException(config + ": preload_mods is not a JSON object; not touching it");
        }
        ObjectNode preload = mods == null ? root.putObject("preload_mods") : (ObjectNode) mods;
        JsonNode entry = preload.get(modId);
        boolean current = entry != null && current(entry.path("infPath").asText(""),
                entry.path("jarPath").asText(""), inf, jar);
        if (!current) {
            ObjectNode updated = preload.putObject(modId);
            updated.put("infPath", inf.toString());
            updated.put("jarPath", jar.toString());
        }
        List<String> stored = new ArrayList<>();
        preload.fieldNames().forEachRemaining(stored::add);
        Map<String, Set<String>> requires = new HashMap<>();
        for (String id : stored) {
            requires.put(id, requires(preload.get(id).path("infPath").asText("")));
        }
        Order order = order(stored, requires);
        boolean reordered = !order.ids().equals(stored);
        if (!current || reordered) {
            ObjectNode ordered = json.createObjectNode();
            order.ids().forEach(id -> ordered.set(id, preload.get(id)));
            root.set("preload_mods", ordered);
            // ZombieBuddy reads the file at every launch
            AtomicWrite.write(config, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        return new Loader.PreloadEntry(current, config, reordered ? order.ids() : List.of(), order.cycles());
    }

    /** The order to preload in, and each group of entries that require each other, in stored order. */
    record Order(List<String> ids, List<List<String>> cycles) {
    }

    /**
     * ZombieBuddy preloads in the stored order: Zabric first, so its transformer is in place for the others, then each
     * entry after the entries it requires, the rest as stored. Entries that require each other keep their stored
     * order. {@code requires}: the mod ids each entry's mod.info requires.
     */
    static Order order(List<String> stored, Map<String, Set<String>> requires) {
        Map<String, Set<String>> deps = new HashMap<>();
        for (String id : stored) {
            Set<String> d = new HashSet<>(requires.getOrDefault(id, Set.of()));
            d.retainAll(stored);
            d.remove(id);
            deps.put(id, d);
        }
        List<String> placed = new ArrayList<>();
        List<String> remaining = new ArrayList<>(stored);
        if (remaining.remove(ZABRIC)) {
            placed.add(ZABRIC);
        }
        List<List<String>> cycles = new ArrayList<>();
        while (!remaining.isEmpty()) {
            String next = remaining.stream().filter(id -> placed.containsAll(deps.get(id))).findFirst().orElse(null);
            if (next != null) {
                remaining.remove(next);
                placed.add(next);
                continue;
            }
            List<String> cycle = firstCycle(remaining, placed, deps);
            remaining.removeAll(cycle);
            placed.addAll(cycle);
            cycles.add(cycle);
        }
        return new Order(List.copyOf(placed), List.copyOf(cycles));
    }

    // the group of entries that require each other and wait for nothing else, with the earliest stored member
    private static List<String> firstCycle(List<String> remaining, List<String> placed, Map<String, Set<String>> deps) {
        for (String id : remaining) {
            List<String> group = remaining.stream()
                    .filter(other -> reaches(id, other, remaining, deps) && reaches(other, id, remaining, deps))
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            boolean waitsForNothingElse = group.stream().flatMap(m -> deps.get(m).stream())
                    .allMatch(d -> placed.contains(d) || group.contains(d));
            if (waitsForNothingElse) {
                return group;
            }
        }
        // every entry left waits for another one left, so they form at least one such group
        throw new IllegalStateException("no cycle among " + remaining + " although none can be placed");
    }

    private static boolean reaches(String from, String to, List<String> within, Map<String, Set<String>> deps) {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> todo = new ArrayDeque<>(deps.get(from));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!within.contains(id) || !seen.add(id)) {
                continue;
            }
            if (id.equals(to)) {
                return true;
            }
            todo.addAll(deps.get(id));
        }
        return false;
    }

    // the require line as the game reads it: comma-separated ids, each with a leading backslash in Build 42
    private static Set<String> requires(String storedInf) throws IOException {
        Path inf;
        try {
            inf = storedInf.isEmpty() ? null : Path.of(storedInf);
        } catch (InvalidPathException e) {
            inf = null;
        }
        // an entry without a readable mod.info requires nothing here: ZombieBuddy drops it at the next premain
        String value = inf == null ? null : modInfoValue(inf, "require");
        Set<String> ids = new HashSet<>();
        if (value != null) {
            for (String part : value.split(",")) {
                String id = part.trim();
                id = id.startsWith("\\") ? id.substring(1).trim() : id;
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    // ZombieBuddy drops an entry at premain without these, which would make every launch register again
    private static void checkPreloadable(String modId, Path inf, Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(modId + ": " + jar + " does not exist");
        }
        if (modInfoValue(inf, "javaPkgName") == null) {
            throw new IllegalStateException(modId + ": " + inf + " has no javaPkgName");
        }
        if (!"true".equalsIgnoreCase(modInfoValue(inf, "javaPreload"))) {
            throw new IllegalStateException(modId + ": " + inf + " does not have javaPreload=true");
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            String value = manifest == null ? null : manifest.getMainAttributes().getValue("ZB-Preload");
            if (!"true".equalsIgnoreCase(value)) {
                throw new IllegalStateException(modId + ": the manifest of " + jar + " does not have ZB-Preload: true");
            }
        }
    }

    // the same paths, or a copy ZombieBuddy would load the same bytes from: a client and a dedicated server on one
    // machine share config.json and would otherwise overwrite each other's entry at every start
    private static boolean current(String storedInf, String storedJar, Path inf, Path jar) {
        if (storedInf.isEmpty() || storedJar.isEmpty()) {
            return false;
        }
        try {
            Path otherInf = Path.of(storedInf);
            Path otherJar = Path.of(storedJar);
            if (Files.isSameFile(otherInf, inf) && Files.isSameFile(otherJar, jar)) {
                return true;
            }
            return Files.isRegularFile(otherInf) && Files.isRegularFile(otherJar)
                    && Arrays.equals(sha256(otherJar), sha256(jar));
        } catch (IOException | RuntimeException e) {
            return false; // the stored file is gone or the path is invalid
        }
    }

    // mod.info as ZombieBuddy reads it: first line wins, keys are case-insensitive, and bytes that are not UTF-8 (another
    // mod's description) do not fail the read
    private static String modInfoValue(Path inf, String key) throws IOException {
        if (!Files.isRegularFile(inf)) {
            return null;
        }
        List<String> lines = new String(Files.readAllBytes(inf), StandardCharsets.UTF_8).lines().toList();
        String prefix = key.toLowerCase(Locale.ROOT) + "=";
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static byte[] sha256(Path file) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every JRE has SHA-256
        }
    }
}
