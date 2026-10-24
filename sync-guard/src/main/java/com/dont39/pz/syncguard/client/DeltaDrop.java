package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.game.PacketScope;
import com.dont39.pz.syncguard.game.Settings;
import com.dont39.pz.syncguard.protocol.ContainerKey;
import zombie.core.network.ByteBufferReader;
import zombie.debug.DebugType;
import zombie.network.GameClient;
import zombie.network.PacketTypes.PacketType;
import zombie.network.fields.ContainerID;
import zombie.network.fields.character.PlayerID;

import java.util.ArrayList;
import java.util.List;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * {@code -Dsyncguard.debugDropDelta=n} (AC1): drops every n-th container or item update the game receives while the
 * client checks, before the game reads it, as a lost update would be. {@code -Dsyncguard.debugDropModule=m[.command]}
 * (AC11) drops a mod's server commands the same way, every n-th of them or every one without debugDropDelta. Main
 * thread only.
 */
final class DeltaDrop {
    private static long seen;
    private static long seenCommands;

    private DeltaDrop() {
    }

    /** True to drop the packet; called for every packet the client handles in game. */
    static boolean drop(PacketType type, ByteBufferReader in) {
        if (Settings.dropModule() != null && type == PacketType.ClientCommand && ClientSync.checking()) {
            return dropCommand(in);
        }
        int every = Settings.dropDelta();
        if (every <= 0 || !ClientSync.checking()) {
            return false;
        }
        PacketScope.Kind kind = PacketScope.kind(type);
        if (kind == null || ++seen % every != 0) {
            return false;
        }
        DebugType.Mod.warn(PREFIX + "debug: dropped incoming %s (update %d) for %s", type.name(), seen,
                target(kind, in));
        ClientSync.dropped();
        return true;
    }

    // a server command starts with its module and command name (GameClient.receiveClientCommand)
    private static boolean dropCommand(ByteBufferReader in) {
        ByteBufferReader r = new ByteBufferReader(in.bb.duplicate());
        String module = r.getUTF();
        if (!module.equals(Settings.dropModule())) {
            return false;
        }
        String command = r.getUTF();
        if (Settings.dropCommand() != null && !command.equals(Settings.dropCommand())) {
            return false;
        }
        int every = Math.max(Settings.dropDelta(), 1);
        if (++seenCommands % every != 0) {
            return false;
        }
        DebugType.Mod.warn(PREFIX + "debug: dropped incoming server command %s.%s (%d)", module, command,
                seenCommands);
        ClientSync.dropped();
        return true;
    }

    // reads the start of the packet as its parse does, from a copy of the reader
    private static String target(PacketScope.Kind kind, ByteBufferReader in) {
        ByteBufferReader r = new ByteBufferReader(in.bb.duplicate());
        try {
            if (kind == PacketScope.Kind.ITEM_FIELDS || kind == PacketScope.Kind.WEAPON_FIELDS) {
                new PlayerID().parse(r, GameClient.connection);
            }
            ContainerID id = new ContainerID();
            id.parse(r, GameClient.connection);
            ContainerKey key = Containers.keyOf(id);
            String where = key == null ? "a floor container" : key.toString();
            return switch (kind) {
                case ITEM_FIELDS, WEAPON_FIELDS, ITEM_MOD_DATA, ITEM_STATS -> "item " + r.getInt() + " in " + where;
                case REMOVE, DELETE -> "items " + ids(r) + " in " + where;
                case REPLACE -> "item " + r.getInt() + " replaced in " + where;
                case ADD -> where;
            };
        } catch (RuntimeException e) {
            // the description only; the packet is dropped either way
            return "a target the mod could not read (" + e + ")";
        }
    }

    private static List<Integer> ids(ByteBufferReader r) {
        int count = r.getShort();
        List<Integer> ids = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            ids.add(r.getInt());
        }
        return ids;
    }
}
