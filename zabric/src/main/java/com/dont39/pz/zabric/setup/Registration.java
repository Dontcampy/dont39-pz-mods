package com.dont39.pz.zabric.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import me.zed_0xff.zombie_buddy.Agent;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Keeps this mod in ZombieBuddy's {@code preload_mods} ({@code config.json}), the only way to get it preloaded: the
 * approval dialog registers preload mods only with a valid signature, which {@code policy=allow-all} never checks.
 * Absolute paths, since ZombieBuddy resolves relative ones against the game folder.
 */
public final class Registration {
    private static final String MOD_ID = "Zabric";
    private static final String PREFIX = "[" + MOD_ID + "] ";

    private static volatile boolean restartNeeded;

    private Registration() {
    }

    /** True when ZombieBuddy will preload the mod only from the next launch on. */
    public static boolean restartNeeded() {
        return restartNeeded;
    }

    /** Never throws: ZombieBuddy calls it. */
    public static void run(boolean preloaded) {
        try {
            register(preloaded);
        } catch (Throwable t) {
            DebugType.Mod.printException(t, PREFIX + "cannot register as a ZombieBuddy preload mod", LogSeverity.Error);
        }
    }

    private static void register(boolean preloaded) throws IOException {
        ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(MOD_ID);
        if (mod == null || mod.getVersionDir() == null) {
            throw new IllegalStateException("the game does not know the folder of mod " + MOD_ID);
        }
        // the copy the game uses this session, which may differ from the one ZombieBuddy preloaded
        Path dir = Path.of(mod.getVersionDir()).toAbsolutePath().normalize();
        Path inf = dir.resolve("mod.info");
        Path jar = dir.resolve("media/java/client/" + MOD_ID + ".jar");
        if (!Files.isRegularFile(inf) || !Files.isRegularFile(jar)) {
            throw new IllegalStateException(dir + " has no mod.info or no " + jar.getFileName());
        }

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
        JsonNode entry = preload.get(MOD_ID);
        boolean current = entry != null && same(entry.path("infPath").asText(""), inf)
                && same(entry.path("jarPath").asText(""), jar);
        if (!current) {
            ObjectNode updated = preload.putObject(MOD_ID);
            updated.put("infPath", inf.toString());
            updated.put("jarPath", jar.toString());
            write(config, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        }
        if (preloaded) {
            if (!current) {
                DebugType.Mod.println(PREFIX + "updated its ZombieBuddy preload entry in %s to %s", config, dir);
            }
            return;
        }
        restartNeeded = true;
        DebugType.Mod.println(
                PREFIX + "%s as a ZombieBuddy preload mod in %s but did not run at game start; restart the game once",
                current ? "is registered" : "registered itself", config);
    }

    private static boolean same(String stored, Path current) {
        if (stored.isEmpty()) {
            return false;
        }
        try {
            return Files.isSameFile(Path.of(stored), current);
        } catch (IOException | RuntimeException e) {
            return false; // the stored file is gone or the path is invalid
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
