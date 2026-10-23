package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.ContainerKey;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.ItemContainer;
import zombie.network.IConnection;
import zombie.network.fields.ContainerID;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Containers as the game addresses them on the wire ({@code ContainerID}), and the mod's keys for them. */
public final class Containers {
    // a ContainerID without floor squares, which keyOf never takes, is a few dozen bytes
    private static final int MAX_WIRE = 256;

    private Containers() {
    }

    /**
     * The key of the container {@code id} addresses, or null for a floor container: the game builds a throwaway one
     * from the square's items on each side, and square object lists are out of scope.
     */
    public static ContainerKey keyOf(ContainerID id) {
        ContainerID.ContainerType type = id.containerType;
        if (type == null) {
            throw new IllegalStateException("a ContainerID without a type");
        }
        return switch (type) {
            case PlayerInventory -> ContainerKey.player(id.playerId.getID());
            case InventoryContainer -> ContainerKey.bag(id.playerId.getID(), id.worldItemId);
            case IsoObject -> ContainerKey.object(id.x, id.y, id.z, id.index);
            case ObjectContainer -> ContainerKey.objectContainer(id.x, id.y, id.z, id.index, id.containerIndex);
            case Vehicle -> ContainerKey.vehicle(id.vid, id.index);
            case ObjectInVehicle -> ContainerKey.vehicleBag(id.vid, id.index, id.worldItemId);
            case DeadBody -> ContainerKey.corpse(id.x, id.y, id.z, id.index);
            case WorldObject -> ContainerKey.worldItem(id.x, id.y, id.z, id.worldItemId);
            case Floor -> null;
            default -> throw new IllegalStateException("a ContainerID of type " + type + ", which this version of"
                    + " the mod does not know");
        };
    }

    /** The bytes the game's packets carry for {@code id}. */
    public static byte[] wire(ContainerID id) {
        ByteBuffer b = ByteBuffer.allocate(MAX_WIRE);
        id.write(new ByteBufferWriter(b));
        return Arrays.copyOf(b.array(), b.position());
    }

    /**
     * The game's address of {@code container} as it is now, or null when the game cannot address it any more (taken
     * out of the world: the game throws "Unable to resolve container location").
     */
    public static ContainerID locate(ItemContainer container) {
        ContainerID id = new ContainerID();
        try {
            id.set(container);
        } catch (IllegalStateException e) {
            return null;
        }
        return id;
    }

    /** Reads {@code wire} as the game's packets do; ends in the game's lookup, so {@code getContainer()} is set. */
    public static ContainerID parse(byte[] wire, IConnection connection) {
        ContainerID id = new ContainerID();
        ByteBufferReader in = new ByteBufferReader(ByteBuffer.wrap(wire));
        id.parse(in, connection);
        if (in.remaining() != 0) {
            throw new IllegalStateException(in.remaining() + " bytes after a container id");
        }
        return id;
    }
}
