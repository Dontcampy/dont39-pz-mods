package com.dont39.pz.zabric.preload;

import com.dont39.pz.zabric.loader.Loader;
import com.dont39.pz.zabric.restart.RestartList;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Keeps a mod running at game start through the loader ({@link Loader#keepPreloaded}).
 * <p>
 * A mod opts in from its Java {@code Main}, so registration only ever happens from code the loader already approved and
 * ran. The loader calls the mods' {@code Main} in the player's mod list order, so Zabric may not be loaded yet: the mod
 * then sets the system property {@link #REQUEST_PREFIX}{@code <modId>} to whether it was preloaded this launch, and
 * Zabric's {@code Main} registers it. Otherwise it calls {@link #register}.
 */
public final class Preload {
    /** Followed by the mod id; the value is {@code true} or {@code false}, as {@code preloadedThisLaunch}. */
    public static final String REQUEST_PREFIX = "zabric.preload.";
    private static final String PREFIX = "[Zabric] ";

    private Preload() {
    }

    /**
     * Registers {@code modId} from the folder the game uses this session, unless its entry already points there or at a
     * byte-identical copy. {@code preloadedThisLaunch}: the mod's {@code PreMain} ran. Puts the mod on the restart list
     * when a restart is needed for its premain code to run. Never throws: it runs inside the loader's call to
     * {@code Main}.
     */
    public static synchronized void register(String modId, boolean preloadedThisLaunch) {
        try {
            update(modId, preloadedThisLaunch);
        } catch (Throwable t) {
            DebugType.Mod.printException(t, PREFIX + "cannot register " + modId + " as a " + Loader.name()
                    + " preload mod", LogSeverity.Error);
        }
    }

    /** Registers the mods that asked before Zabric was loaded. Called by Zabric's Main, after its own registration. */
    public static synchronized void registerRequested() {
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(REQUEST_PREFIX)) {
                String value = System.clearProperty(key);
                if (value != null) {
                    register(key.substring(REQUEST_PREFIX.length()), Boolean.parseBoolean(value));
                }
            }
        }
    }

    private static void update(String modId, boolean preloaded) throws IOException {
        ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(modId);
        if (mod == null || mod.getVersionDir() == null) {
            throw new IllegalStateException("the game does not know the folder of mod " + modId);
        }
        // the copy the game uses this session, which may differ from the one the loader preloaded
        Path versionDir = Path.of(mod.getVersionDir()).toAbsolutePath().normalize();
        Path commonDir = mod.getCommonDir() == null ? null
                : Path.of(mod.getCommonDir()).toAbsolutePath().normalize();
        Loader.PreloadEntry entry = Loader.keepPreloaded(modId, versionDir, commonDir);
        if (preloaded && entry.wasCurrent()) {
            return;
        }
        RestartList.add(modId);
        if (entry.wasCurrent()) {
            DebugType.Mod.println(PREFIX + "%s is registered as a %s preload mod in %s but did not run at game start;"
                    + " restart the game once", modId, Loader.name(), entry.location());
        } else {
            DebugType.Mod.println(PREFIX + "%s registered as a %s preload mod in %s from %s; restart the game once",
                    modId, Loader.name(), entry.location(), versionDir);
        }
    }
}
