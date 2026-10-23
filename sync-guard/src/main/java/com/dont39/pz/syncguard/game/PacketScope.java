package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.Protocol;
import zombie.network.PacketTypes.PacketType;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.RemoveInventoryItemFromContainerPacket;
import zombie.network.packets.ReplaceInventoryItemInContainerPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemDeletePacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;

import java.util.ArrayList;
import java.util.List;

/** The game's packet types the mod checks, looked up by name, and their reliability and channel. */
public final class PacketScope {
    /** The packet types in scope, by the game's names. */
    public enum Kind {
        ADD("AddInventoryItemToContainer", AddInventoryItemToContainerPacket.class),
        REMOVE("RemoveInventoryItemFromContainer", RemoveInventoryItemFromContainerPacket.class),
        REPLACE("ReplaceInventoryItemInContainer", ReplaceInventoryItemInContainerPacket.class),
        DELETE("SyncItemDelete", SyncItemDeletePacket.class),
        ITEM_STATS("ItemStats", ItemStatsPacket.class),
        ITEM_FIELDS("SyncItemFields", SyncItemFieldsPacket.class),
        ITEM_MOD_DATA("SyncItemModData", SyncItemModDataPacket.class),
        WEAPON_FIELDS("SyncHandWeaponFields", SyncHandWeaponFieldsPacket.class);

        private final String typeName;
        private final Class<? extends INetworkPacket> handler;
        private PacketType type;

        Kind(String typeName, Class<? extends INetworkPacket> handler) {
            this.typeName = typeName;
            this.handler = handler;
        }

        public PacketType type() {
            return type;
        }
    }

    // by the type's ordinal; replaced whole, read on any thread
    private static volatile Kind[] kinds = new Kind[0];
    private static final List<Runnable> undo = new ArrayList<>();

    private PacketScope() {
    }

    /** Throws when a type is missing or handled by another class than the mod was built for. */
    public static synchronized void resolve() {
        if (kinds.length > 0) {
            return;
        }
        Kind[] byOrdinal = new Kind[PacketType.values().length];
        for (Kind k : Kind.values()) {
            PacketType t;
            try {
                t = PacketType.valueOf(k.typeName);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("the game has no packet type " + k.typeName, e);
            }
            if (t.handler != k.handler) {
                throw new IllegalStateException("the game handles packet type " + k.typeName + " with " + t.handler
                        + " instead of " + k.handler.getName());
            }
            k.type = t;
            byOrdinal[t.ordinal()] = k;
        }
        kinds = byOrdinal;
    }

    /** Null for a type out of scope. */
    public static Kind kind(PacketType type) {
        Kind[] byOrdinal = kinds;
        int i = type.ordinal();
        return i < byOrdinal.length ? byOrdinal[i] : null;
    }

    /**
     * Puts the item field packets on the container packets' ordered channel, so a field update cannot overtake its
     * item's add (R2). Affects only what this side sends: the sender picks reliability and channel.
     */
    public static synchronized void apply() {
        resolve();
        if (!undo.isEmpty()) {
            return;
        }
        for (Kind k : new Kind[] {Kind.ITEM_FIELDS, Kind.ITEM_MOD_DATA, Kind.WEAPON_FIELDS, Kind.ITEM_STATS}) {
            PacketType t = k.type;
            int reliability = t.packetReliability;
            byte channel = t.orderingChannel;
            undo.add(() -> {
                t.packetReliability = reliability;
                t.orderingChannel = channel;
            });
            t.packetReliability = Protocol.RELIABLE_ORDERED;
            t.orderingChannel = Protocol.SHARED_CHANNEL;
        }
    }

    /** Puts back what {@link #apply} found; nothing when it did not run. */
    public static synchronized void restore() {
        undo.forEach(Runnable::run);
        undo.clear();
    }
}
