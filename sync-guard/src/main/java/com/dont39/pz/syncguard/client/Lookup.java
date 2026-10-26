package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.core.raknet.UdpConnection;
import zombie.inventory.ItemContainer;
import zombie.vehicles.VehiclePart;

/** The client's container for a key the server sent, through the game's own lookup. Main thread only. */
final class Lookup {
    /**
     * {@code container}: the client's, or null when it has none addressed under that key (not loaded yet, a parent or
     * bag that is gone, or a stand-in the game's lookup made up for an index out of range). {@code key}: null for a
     * floor container, which the server never sends. {@code part}: the vehicle part whose items the container holds.
     */
    record Found(ContainerKey key, ItemContainer container, VehiclePart part) {
        String name(WireKey wire) {
            String where = key != null ? key.toString() : "a floor container";
            return wire.isItem() ? "item " + wire.itemId() + " in " + where : where;
        }
    }

    private Lookup() {
    }

    static Found find(WireKey wire, UdpConnection connection) {
        ContainerKey key = Containers.keyOf(wire.containerBytes());
        Containers.Resolved at = Containers.find(wire.containerBytes(), connection);
        if (at == null) {
            return new Found(key, null, null);
        }
        ItemContainer container = key != null && Containers.live(at.container(), key) ? at.container() : null;
        return new Found(key, container, at.part());
    }
}
