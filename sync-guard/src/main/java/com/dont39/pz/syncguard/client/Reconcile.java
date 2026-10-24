package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.ItemDigests;
import com.dont39.pz.syncguard.protocol.ContainerDigest;
import com.dont39.pz.syncguard.protocol.Message.Snapshot;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.Lua.LuaEventManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.fields.ContainerID;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;
import zombie.vehicles.VehiclePart;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Applies a snapshot in place (R6, D5), by item id and with the game's own packet classes, so fields mean what the
 * game's updates mean: extras off the client, missing items added from the snapshot's item list, then each item's
 * field payloads in the order the server wrote them. Nothing here sends to the server. Main thread only.
 */
final class Reconcile {
    enum Outcome {
        /** Something changed and the digest now matches, apart from items in both whose fields still differ. */
        REPAIRED,
        /** Nothing needed to change: the client caught up since it asked (stuck items aside, as for REPAIRED). */
        MATCHED,
        /** Items or their types still differ, or the game failed on the snapshot. */
        UNREPAIRABLE,
        /** The client no longer has the object under that key. */
        NOT_FOUND
    }

    /** An item the snapshot left differing: its digest on the client after it, and the server's. */
    record Stuck(int id, long mine, long server) {
    }

    /**
     * {@code failure}: why it is unrepairable; {@code cause}: the game's exception behind it, if any. {@code stuck}:
     * items that still differ while everything else now matches.
     */
    record Result(Outcome outcome, String name, List<Integer> added, List<Integer> removed, List<Integer> changed,
            List<Stuck> stuck, String failure, Throwable cause) {
        boolean changedAnything() {
            return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        }
    }

    /** The game failed on this snapshot, or its state does not take it: only this key fails, the session goes on. */
    private static final class Failed extends RuntimeException {
        Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Reconcile() {
    }

    /** {@code diff}: null unless the field diff is on. Throws only for what this version of the mod gets wrong. */
    static Result apply(Snapshot s, UdpConnection connection, FieldDiff diff) {
        WireKey wire = s.key();
        Lookup.Found found = Lookup.find(wire, connection);
        String name = found.name(wire);
        ItemContainer container = found.container();
        if (container == null) {
            return new Result(Outcome.NOT_FOUND, name, List.of(), List.of(), List.of(), List.of(), null, null);
        }
        Set<Integer> serverIds = new HashSet<>();
        s.items().forEach(i -> serverIds.add(i.id()));
        if (wire.isItem() && !serverIds.equals(Set.of(wire.itemId()))) {
            throw new IllegalStateException("the snapshot of " + name + " holds items " + serverIds);
        }
        Map<Integer, InventoryItem> mine = covered(container, wire);
        Map<Integer, Long> before = digests(mine);
        Map<Integer, FieldDiff.Fields> beforeFields = diff != null ? FieldDiff.of(mine.values()) : null;
        Map<Integer, FieldDiff.Fields> serverFields = null;
        try {
            AddInventoryItemToContainerPacket list = itemList(s, name, container, connection);
            if (diff != null) {
                serverFields = FieldDiff.of(list.items.stream().filter(Objects::nonNull).toList());
            }
            if (!wire.isItem()) {
                remove(container, found.id(), mine.values().stream().filter(i -> !serverIds.contains(i.getID()))
                        .toList());
            }
            Set<Integer> missing = new HashSet<>(serverIds);
            missing.removeAll(mine.keySet());
            add(list, missing, connection);
            for (Snapshot.Item item : s.items()) {
                payload(new SyncItemFieldsPacket(), item.fields(), "SyncItemFields", item.id(), connection);
                payload(new SyncItemModDataPacket(), item.modData(), "SyncItemModData", item.id(), connection);
                payload(new ItemStatsPacket(), item.stats(), "ItemStats", item.id(), connection);
                payload(new SyncHandWeaponFieldsPacket(), item.weapon(), "SyncHandWeaponFields", item.id(),
                        connection);
            }
        } catch (Failed f) {
            return new Result(Outcome.UNREPAIRABLE, name, List.of(), List.of(), List.of(), List.of(), f.getMessage(),
                    f.getCause());
        }
        Map<Integer, InventoryItem> after = covered(container, wire);
        List<Integer> added = after.keySet().stream().filter(id -> !mine.containsKey(id)).toList();
        List<Integer> removed = mine.keySet().stream().filter(id -> !after.containsKey(id)).toList();
        List<Integer> changed = new ArrayList<>();
        digests(after).forEach((id, digest) -> {
            Long was = before.get(id);
            if (was != null && was != digest.longValue()) {
                changed.add(id);
            }
        });
        Map<Integer, Long> server = new HashMap<>();
        s.items().forEach(i -> server.put(i.id(), i.digest()));
        Map<Integer, Long> now = digests(after);
        if (diff != null) {
            diff.applied(wire, name, beforeFields, FieldDiff.of(after.values()), serverFields, server);
        }
        // items in both whose fields still differ; everything else must match, with those taken as the server has them
        List<Stuck> stuck = new ArrayList<>();
        now.forEach((id, digest) -> {
            Long theirs = server.get(id);
            if (theirs != null && theirs.longValue() != digest) {
                stuck.add(new Stuck(id, digest, theirs));
            }
        });
        boolean matches;
        if (wire.isItem()) {
            matches = after.size() == 1 && server.containsKey(wire.itemId());
        } else {
            ContainerDigest d = new ContainerDigest();
            after.forEach((id, item) -> d.add(id, item.getFullType(), server.getOrDefault(id, now.get(id))));
            matches = d.get() == s.digest();
        }
        if (!matches) {
            return new Result(Outcome.UNREPAIRABLE, name, added, removed, changed, List.of(),
                    "its digest still differs from the server's after the snapshot", null);
        }
        Result result = new Result(Outcome.REPAIRED, name, added, removed, changed, stuck, null, null);
        return result.changedAnything() ? result
                : new Result(Outcome.MATCHED, name, added, removed, changed, stuck, null, null);
    }

    /** The client's items the snapshot covers: all of a container's, or the one item. */
    private static Map<Integer, InventoryItem> covered(ItemContainer container, WireKey wire) {
        Map<Integer, InventoryItem> items = new LinkedHashMap<>();
        if (wire.isItem()) {
            InventoryItem item = container.getItemWithID(wire.itemId());
            if (item != null) {
                items.put(item.getID(), item);
            }
        } else {
            container.getItems().forEach(item -> items.put(item.getID(), item));
        }
        return items;
    }

    private static Map<Integer, Long> digests(Map<Integer, InventoryItem> items) {
        Map<Integer, Long> out = new LinkedHashMap<>();
        items.forEach((id, item) -> out.put(id, ItemDigests.item(item)));
        return out;
    }

    // the server's items as AddInventoryItemToContainer carries them, read but not added yet
    private static AddInventoryItemToContainerPacket itemList(Snapshot s, String name, ItemContainer container,
            UdpConnection connection) {
        if (s.add().length == 0) {
            throw new IllegalStateException("the snapshot of " + name + " has no item list");
        }
        AddInventoryItemToContainerPacket list = new AddInventoryItemToContainerPacket();
        game("reading the snapshot's item list", () -> list.parse(reader(s.add()), connection));
        if (list.containerId.getContainer() != container) {
            throw new Failed("its item list names another container on this client", null);
        }
        return list;
    }

    /**
     * Takes items off the client as the game's own updates do, without a request: worn ones first as
     * SyncClothingPacket.processClient does, then the container's removal, which also empties the hands (as
     * RemoveInventoryItemFromContainerPacket.processClient). Attached items are left to the hotbar's own update.
     */
    private static void remove(ItemContainer container, ContainerID id, List<InventoryItem> extras) {
        if (extras.isEmpty()) {
            return;
        }
        IsoGameCharacter owner = container.getCharacter();
        if (owner instanceof IsoPlayer player && player.isLocalPlayer()) {
            List<InventoryItem> worn = extras.stream().filter(player::isEquippedClothing).toList();
            if (!worn.isEmpty()) {
                game("taking off worn items", () -> {
                    worn.forEach(item -> player.getWornItems().remove(item));
                    player.onWornItemsChanged();
                    player.resetModelNextFrame();
                    LuaEventManager.triggerEvent("OnClothingUpdated", player);
                });
            }
        }
        game("removing items", () -> {
            extras.forEach(item -> container.removeItemWithID(item.getID()));
            container.setExplored(true);
            VehiclePart part = id.getPart();
            if (part != null) {
                part.setContainerContentAmount(container.getCapacityWeight());
            }
        });
    }

    private static void add(AddInventoryItemToContainerPacket list, Set<Integer> missing, UdpConnection connection) {
        // the game would print a "dupe" line for each item already there
        list.items.removeIf(item -> item == null || !missing.contains(item.getID()));
        if (list.items.isEmpty()) {
            return;
        }
        if (!gameCheck("checking the items to add", () -> list.isConsistent(connection))) {
            throw new Failed("the game found the items to add inconsistent", null);
        }
        game("adding items", () -> list.processClient(connection));
    }

    // as the game handles its own: one it finds inconsistent is skipped, and the digest check reports what is left
    private static void payload(INetworkPacket packet, byte[] bytes, String type, int itemId,
            UdpConnection connection) {
        if (bytes.length == 0) {
            return;
        }
        game("applying " + type + " to item " + itemId, () -> {
            packet.parse(reader(bytes), connection);
            if (packet.isConsistent(connection)) {
                packet.processClient(connection);
            }
        });
    }

    private static ByteBufferReader reader(byte[] bytes) {
        return new ByteBufferReader(ByteBuffer.wrap(bytes));
    }

    private static void game(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            throw new Failed("the game failed " + what, e);
        }
    }

    private static boolean gameCheck(String what, BooleanSupplier call) {
        try {
            return call.getAsBoolean();
        } catch (RuntimeException e) {
            throw new Failed("the game failed " + what, e);
        }
    }
}
