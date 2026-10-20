package com.pzlauncher.zabric.mods;

import com.pzlauncher.zabric.entrypoint.Lifecycle;
import com.pzlauncher.zabric.internal.GameFolders;
import com.pzlauncher.zabric.internal.GameSide;
import com.pzlauncher.zabric.mods.impl.LaunchOverrides;
import com.pzlauncher.zabric.mods.impl.OverrideRequest;

import zombie.network.GameServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Makes the next launch take installed Workshop items from other folders: for each item, a folder of mod folders
 * (laid out like the item's {@code mods} folder) takes the place of the item's {@code mods} folder in every pass over
 * the mod list of that launch, so its mods' Lua and Java come from that folder, the startup pass included. Zabric's
 * widening reads the lists of the mods in those folders at that launch's start.
 * <p>
 * The request is used once: the next game start reads and deletes it, so a crash later in that launch does not carry
 * it over. A request that is not valid at that start is discarded whole, with an error in the log. Items that hold
 * ZombieBuddy, Zabric or ZabricKotlin are refused: they run before the first pass.
 * <p>
 * Call on the main thread, from a main entrypoint or later. Every method throws when Zabric did not run at game start
 * or its entrypoints are stopped for the session.
 */
public final class LaunchOverride {
    private LaunchOverride() {
    }

    /**
     * Requests {@code modsByItem} (Workshop item id → absolute path of a folder holding mod folders) for the next
     * launch of this side (client or dedicated server), replacing a pending request; an empty map withdraws it. Throws
     * naming each invalid item (an id that is not digits, a relative path, a folder without a mod folder, an item that
     * holds ZombieBuddy, Zabric or ZabricKotlin), and then writes nothing.
     */
    public static void request(Map<String, Path> modsByItem) throws IOException {
        Lifecycle.requireRunning("LaunchOverride.request");
        OverrideRequest.write(OverrideRequest.file(side()), modsByItem, GameFolders.workshopContent());
    }

    /** The request the next launch of this side will read, or an empty map. */
    public static Map<String, Path> pending() throws IOException {
        Lifecycle.requireRunning("LaunchOverride.pending");
        return OverrideRequest.read(OverrideRequest.file(side()));
    }

    /**
     * What this launch applies: the requested items the game listed among its installed Workshop items, with their
     * folders; empty without a request, or when the request was discarded. Throws when the override failed this launch
     * (the log has the error): then the game uses the items' own folders from that point on.
     */
    public static Map<String, Path> current() {
        Lifecycle.requireRunning("LaunchOverride.current");
        return LaunchOverrides.current();
    }

    private static GameSide side() {
        return GameServer.server ? GameSide.SERVER : GameSide.CLIENT;
    }
}
