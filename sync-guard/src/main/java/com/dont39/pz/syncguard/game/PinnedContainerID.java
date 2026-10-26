package com.dont39.pz.syncguard.game;

import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.network.fields.ContainerID;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;

import java.util.Objects;

/**
 * The container of a snapshot's game payloads for a nested bag, which no game id addresses: the packet classes set,
 * write, read and look up their container through it. On the server it stays Undefined, one byte that the game reads
 * without a lookup; on the client it stands for the bag found from the snapshot's own address.
 */
public final class PinnedContainerID extends ContainerID {
    private final ItemContainer container;

    private PinnedContainerID(ItemContainer container) {
        this.container = Objects.requireNonNull(container);
    }

    /** Points {@code packet}'s container at {@code container}; before setData on the server, parse on a client. */
    public static <P extends INetworkPacket> P pin(P packet, ItemContainer container) {
        PinnedContainerID id = new PinnedContainerID(container);
        if (packet instanceof AddInventoryItemToContainerPacket p) {
            p.containerId = id;
        } else if (packet instanceof SyncItemFieldsPacket p) {
            p.containerId = id;
        } else if (packet instanceof SyncItemModDataPacket p) {
            p.containerId = id;
        } else if (packet instanceof ItemStatsPacket p) {
            p.containerId = id;
        } else if (packet instanceof SyncHandWeaponFieldsPacket p) {
            p.containerId = id;
        } else {
            throw new IllegalArgumentException("a " + packet.getClass().getName() + " is not a snapshot payload");
        }
        return packet;
    }

    // ContainerID.set throws for some nested bags (an outer bag on the floor has no parent object)
    @Override
    public void set(ItemContainer container) {
    }

    @Override
    public void setFloor(ItemContainer container, IsoGridSquare square) {
    }

    @Override
    public void findObject() {
    }

    @Override
    public ItemContainer getContainer() {
        return container;
    }
}
