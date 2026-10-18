package com.pzlauncher.zabric.entrypoint;

import com.pzlauncher.zabric.loader.Loader;
import com.pzlauncher.zabric.restart.RestartList;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ChooseGameInfo;

import java.io.IOException;
import java.nio.file.Path;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * Keeps a mod running at game start through the loader ({@link Loader#keepPreloaded}): Zabric itself, and every mod that
 * declares a preLaunch entrypoint. Only for mods the loader approved and loaded, so registration only ever follows code
 * the loader approved.
 */
final class Preload {
    private Preload() {
    }

    /**
     * Registers {@code modId} from the folder the game uses this session, unless its entry already points there or at a
     * byte-identical copy. {@code preloadedThisLaunch}: its game-start code ran. Puts the mod on the restart list when a
     * restart is needed for that code to run. Never throws: it runs inside the loader's pass over the mod list.
     */
    static synchronized void register(String modId, boolean preloadedThisLaunch) {
        try {
            update(modId, preloadedThisLaunch);
        } catch (Throwable t) {
            DebugType.Mod.printException(t, PREFIX + "cannot register " + modId + " as a " + Loader.name()
                    + " preload mod", LogSeverity.Error);
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
