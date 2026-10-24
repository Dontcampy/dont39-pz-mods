package com.dont39.pz.syncguard.server;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.game.ItemDigests;
import com.dont39.pz.syncguard.game.Net;
import com.dont39.pz.syncguard.game.Settings;
import com.dont39.pz.syncguard.protocol.ContainerDigest;
import com.dont39.pz.syncguard.protocol.ItemKey;
import com.dont39.pz.syncguard.protocol.Message.Snapshot;
import com.dont39.pz.syncguard.protocol.Protocol;
import com.dont39.pz.syncguard.protocol.SnapshotWriter;
import com.dont39.pz.syncguard.protocol.SnapshotWriter.Payload;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.ContainerID;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * Snapshots (R6): an object's state written with the game's own packet classes, so the client applies it with the
 * same code as the game's updates. Main thread only.
 */
final class Snapshots {
    // the connection's send buffer is 1,000,000 bytes, the packet header 3 of them
    private static final ByteBuffer SCRATCH = ByteBuffer.allocate(999_000);

    private Snapshots() {
    }

    /** Sends queued snapshots up to the per-tick budget; a single one may exceed the bytes. */
    static void serve(Peer p) {
        if (p.snapshots.isEmpty()) {
            return;
        }
        IsoPlayer player = p.player();
        if (player == null) {
            return;
        }
        int sent = 0;
        int bytes = 0;
        while (!p.snapshots.isEmpty() && sent < ServerSync.SNAPSHOTS_PER_TICK
                && bytes < ServerSync.SNAPSHOT_BYTES_PER_TICK) {
            Peer.Job job = p.snapshots.poll();
            p.queued.remove(job.key());
            // read once already when asked; null when the parent left since
            ContainerID id = ServerSync.parse(p, job.wire().containerBytes());
            bytes += send(p, job, id == null ? null : id.getContainer(), player);
            sent++;
        }
    }

    private static int send(Peer p, Peer.Job job, ItemContainer container, IsoPlayer requester) {
        long version = p.frame;
        ByteBuffer out = SCRATCH;
        out.clear();
        boolean tooLarge = false;
        Snapshot.State state;
        ServerSync.ownWrite(true);
        try {
            state = write(out, job, container, version, requester);
        } catch (BufferOverflowException e) {
            tooLarge = true;
            state = Snapshot.State.TOO_LARGE;
            out.clear();
            SnapshotWriter.start(out, job.wire(), version, state, 0).add(null).finish();
        } finally {
            ServerSync.ownWrite(false);
        }
        if (state == Snapshot.State.PRESENT) {
            p.versions.put(job.key(), version);
        } else {
            p.versions.remove(job.key());
        }
        if (tooLarge) {
            DebugType.Mod.warn(PREFIX + "%s: %s does not fit into one packet; it is not repaired", p.name(),
                    job.key());
        } else {
            // the server's half of a repair's log (R9); the client logs what the snapshot changed
            DebugType.Mod.println(PREFIX + "%s: sent the server's state of %s (%s), as %s", p.name(), job.key(),
                    state == Snapshot.State.PRESENT ? "present" : "no longer there",
                    job.opened() ? "the client's own check of it differed" : "it differed from a sync frame entry on"
                            + " the client");
        }
        out.flip();
        int bytes = Net.send(p.connection, Protocol.SHARED_CHANNEL, b -> b.put(out));
        p.session.snapshot(tooLarge, bytes);
        return bytes;
    }

    private static Snapshot.State write(ByteBuffer out, Peer.Job job, ItemContainer container, long version,
            IsoPlayer requester) {
        boolean live = Containers.live(container, job.key().container());
        InventoryItem only = null;
        if (live && job.key() instanceof ItemKey ik) {
            only = container.getItemWithID(ik.itemId());
            live = only != null;
        }
        if (!live) {
            SnapshotWriter.start(out, job.wire(), version, Snapshot.State.GONE, 0).add(null).finish();
            return Snapshot.State.GONE;
        }
        List<InventoryItem> items = only != null ? List.of(only) : new ArrayList<>(container.getItems());
        long[] digests = new long[items.size()];
        ContainerDigest whole = new ContainerDigest();
        for (int i = 0; i < digests.length; i++) {
            InventoryItem item = items.get(i);
            digests[i] = ItemDigests.item(item);
            whole.add(item.getID(), item.getFullType(), digests[i]);
        }
        long digest = only != null ? digests[0] : whole.get();
        // the game's field packets name a player; the owner's, or the asking player for a world container
        IsoPlayer player = container.getCharacter() instanceof IsoPlayer owner ? owner : requester;
        SnapshotWriter w = SnapshotWriter.start(out, job.wire(), version, Snapshot.State.PRESENT, digest);
        w.add(b -> {
            AddInventoryItemToContainerPacket add = new AddInventoryItemToContainerPacket();
            add.setData(container, new ArrayList<>(items));
            add.write(new ByteBufferWriter(b));
        });
        if (Settings.fieldDiff()) {
            logValues(job, items);
        }
        for (int i = 0; i < digests.length; i++) {
            InventoryItem item = items.get(i);
            w.item(item.getID(), digests[i], fields(player, item), item.hasModData() ? modData(item) : null,
                    stats(container, item), item instanceof HandWeapon weapon ? weapon(player, weapon) : null);
        }
        w.finish();
        return Snapshot.State.PRESENT;
    }

    /** Longest value list logged per item; clothing visuals alone have over a hundred values. */
    private static final int LOGGED_CHARS = 4000;

    // the client's field diff compares with the item as the snapshot's save restores it; this is the live item
    private static void logValues(Peer.Job job, List<InventoryItem> items) {
        for (InventoryItem item : items) {
            StringBuilder b = new StringBuilder();
            ItemDigests.fields(item).values().forEach((name, value) -> {
                // unset clothing visuals
                if (!(name.startsWith("visual.") && value.equals("0.0"))) {
                    b.append(b.isEmpty() ? "" : ", ").append(name).append('=').append(value);
                }
            });
            String values = b.length() > LOGGED_CHARS ? b.substring(0, LOGGED_CHARS) + "..." : b.toString();
            DebugType.Mod.println(PREFIX + "field values: %s item %d (%s): %s", job.key(), item.getID(),
                    item.getFullType(), values);
        }
    }

    private static Payload fields(IsoPlayer player, InventoryItem item) {
        return b -> {
            SyncItemFieldsPacket packet = new SyncItemFieldsPacket();
            packet.setData(player, item);
            packet.write(new ByteBufferWriter(b));
        };
    }

    private static Payload modData(InventoryItem item) {
        return b -> {
            SyncItemModDataPacket packet = new SyncItemModDataPacket();
            packet.setData(item);
            packet.write(new ByteBufferWriter(b));
        };
    }

    private static Payload stats(ItemContainer container, InventoryItem item) {
        return b -> {
            ItemStatsPacket packet = new ItemStatsPacket();
            packet.setData(container, item);
            packet.write(new ByteBufferWriter(b));
        };
    }

    private static Payload weapon(IsoPlayer player, HandWeapon weapon) {
        return b -> {
            SyncHandWeaponFieldsPacket packet = new SyncHandWeaponFieldsPacket();
            packet.setData(player, weapon);
            packet.write(new ByteBufferWriter(b));
        };
    }
}
