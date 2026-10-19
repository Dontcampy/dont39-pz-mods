package com.pzlauncher.zabric.restart;

import zombie.debug.DebugType;
import zombie.gameStates.ChooseGameInfo;
import zombie.network.GameServer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.pzlauncher.zabric.internal.Log.PREFIX;

/**
 * The mods that take effect only after a restart: preload mods registered this launch, and mods whose widening list
 * appeared or changed while the game ran. The client shows it in a dialog on the main menu; a server logs it.
 */
public final class RestartList {
    // ids only: add() can run on the render thread, display names come from the game's mod list
    private static final Set<String> ids = new LinkedHashSet<>();

    private RestartList() {
    }

    public static void add(String modId) {
        synchronized (ids) {
            if (!ids.add(modId)) {
                return;
            }
        }
        // a server has no main menu; the line repeats with the whole list whenever a mod joins it
        if (GameServer.server) {
            DebugType.Mod.warn(PREFIX + "restart the server once so that these mods take effect: %s",
                    String.join(", ", names()));
        }
    }

    /** Display names, in the order the mods joined the list. Call on the main thread. */
    public static List<String> names() {
        List<String> copy;
        synchronized (ids) {
            copy = new ArrayList<>(ids);
        }
        return copy.stream().map(RestartList::displayName).toList();
    }

    private static String displayName(String modId) {
        ChooseGameInfo.Mod mod = ChooseGameInfo.getAvailableModDetails(modId);
        String name = mod == null ? null : mod.getName();
        return name == null || name.isBlank() ? modId : name;
    }
}
