package com.dont39.pz.zabric.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.zed_0xff.zombie_buddy.Agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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

        Path config = Agent.configDir().resolve("config.json");
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
        if (!current || !zabricFirst(preload)) {
            root.set("preload_mods", withZabricFirst(json, preload));
            write(config, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        return new Loader.PreloadEntry(current, config);
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

    private static boolean zabricFirst(ObjectNode preload) {
        Iterator<String> names = preload.fieldNames();
        return !preload.has(ZABRIC) || names.next().equals(ZABRIC);
    }

    // ZombieBuddy preloads in this order; Zabric must come first so its transformer is in place for the others
    private static ObjectNode withZabricFirst(ObjectMapper json, ObjectNode preload) {
        ObjectNode ordered = json.createObjectNode();
        if (preload.has(ZABRIC)) {
            ordered.set(ZABRIC, preload.get(ZABRIC));
        }
        preload.fields().forEachRemaining(e -> {
            if (!e.getKey().equals(ZABRIC)) {
                ordered.set(e.getKey(), e.getValue());
            }
        });
        return ordered;
    }

    // mod.info as ZombieBuddy reads it: first line wins, keys are case-insensitive
    private static String modInfoValue(Path inf, String key) throws IOException {
        if (!Files.isRegularFile(inf)) {
            return null;
        }
        List<String> lines = Files.readAllLines(inf, StandardCharsets.UTF_8);
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

    // ZombieBuddy reads the file at every launch; never leave it half written
    private static void write(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
