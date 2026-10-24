package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.ItemContainer;
import zombie.network.fields.ContainerID;

/** The client's container for a key the server sent, through the game's own lookup. Main thread only. */
final class Lookup {
    /**
     * {@code container}: the client's, or null when it has none the game addresses under that key (not loaded yet, a
     * parent that is gone, or a stand-in the game's lookup made up for an index out of range). {@code key}: null for a
     * floor container, which the server never sends.
     */
    record Found(ContainerID id, ContainerKey key, ItemContainer container) {
        String name(WireKey wire) {
            String where = key != null ? key.toString() : "a container the game could not find";
            return wire.isItem() ? "item " + wire.itemId() + " in " + where : where;
        }
    }

    private Lookup() {
    }

    static Found find(WireKey wire, UdpConnection connection) {
        ContainerID id = Containers.parse(wire.containerBytes(), connection);
        if (id == null) {
            return new Found(null, null, null);
        }
        ContainerKey key = Containers.keyOf(wire.containerBytes());
        ItemContainer container = key != null && Containers.live(id.getContainer(), key) ? id.getContainer() : null;
        return new Found(id, key, container);
    }
}
