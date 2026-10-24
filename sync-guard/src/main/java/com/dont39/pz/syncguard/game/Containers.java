package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.ContainerWire;
import com.dont39.pz.syncguard.protocol.ProtocolException;
import zombie.core.network.ByteBufferReader;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.network.IConnection;
import zombie.network.fields.ContainerID;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Containers as the game addresses them on the wire ({@code ContainerID}), and the mod's keys for them. */
public final class Containers {
    // a ContainerID without floor squares is a few dozen bytes
    private static final int MAX_WIRE = 256;

    private Containers() {
    }

    /** Throws when the game's container types are not the ones {@link ContainerWire} reads. */
    public static void check() {
        List<String> types = Arrays.stream(ContainerID.ContainerType.values()).map(Enum::name).toList();
        if (!types.equals(ContainerWire.TYPES)) {
            throw new IllegalStateException("the game's container types are " + types + ", this version of the mod"
                    + " reads " + ContainerWire.TYPES);
        }
    }

    /**
     * The key of the container {@code id} addresses, or null for a floor container (the game builds a throwaway one
     * from the square's items on each side, and square object lists are out of scope) and for one the game leaves
     * undefined (the loot window's own floor container off any square).
     */
    public static ContainerKey keyOf(ContainerID id) {
        ContainerID.ContainerType type = id.getContainerType();
        if (type == null) {
            throw new IllegalStateException("a ContainerID without a type");
        }
        // a floor id lists squares, which wire() has no room for
        return type == ContainerID.ContainerType.Floor || type == ContainerID.ContainerType.Undefined ? null
                : keyOf(wire(id));
    }

    /** The key of the game's bytes {@code wire}; null for a floor container. */
    public static ContainerKey keyOf(byte[] wire) {
        try {
            return ContainerWire.decode(wire);
        } catch (ProtocolException e) {
            throw new IllegalStateException("a container id this version of the mod cannot read: " + e.getMessage(),
                    e);
        }
    }

    /** The bytes the game's packets carry for {@code id}. */
    public static byte[] wire(ContainerID id) {
        ByteBuffer b = ByteBuffer.allocate(MAX_WIRE);
        id.write(b);
        return Arrays.copyOf(b.array(), b.position());
    }

    /**
     * The game's address of {@code container} as it is now, or null when the game cannot address it any more: taken
     * out of the world, or its parent object off its square.
     */
    public static ContainerID locate(ItemContainer container) {
        ContainerID id = new ContainerID();
        try {
            id.set(container);
        } catch (RuntimeException e) {
            // "Unable to resolve container location" for no place; a parent object without a square fails inside set
            return null;
        }
        return id;
    }

    /** A container's key and the game's bytes for it. */
    public record Address(ContainerKey key, byte[] wire) {
    }

    /**
     * Where a client finds {@code container}, or null when the game's own lookup of its address would not end at
     * this very container: a floor container, one not in the world, a bag in a world or corpse container (the game
     * addresses it as its outer container), an object no longer on its square. Server only: resolves players by
     * online id, as a client does.
     */
    public static Address address(ItemContainer container) {
        ContainerID id = locate(container);
        if (id == null) {
            return null;
        }
        ContainerID.ContainerType type = id.getContainerType();
        if (type == null || type == ContainerID.ContainerType.Undefined || type == ContainerID.ContainerType.Floor) {
            return null;
        }
        byte[] wire = wire(id);
        ContainerKey key = keyOf(wire);
        // indexOf of an object no longer on its square; the game's lookup would log an error for it
        if (key.kind().square() && key.index() < 0) {
            return null;
        }
        ContainerID back = parse(wire, null);
        return back != null && back.getContainer() == container ? new Address(key, wire) : null;
    }

    /**
     * Whether {@code container} is still the one the game addresses as {@code key}: not taken out of the world, not
     * at another object index, not a stand-in the game's lookup made up for an index out of range.
     */
    public static boolean live(ItemContainer container, ContainerKey key) {
        if (container == null) {
            return false;
        }
        ContainerID now = locate(container);
        return now != null && key.equals(keyOf(now));
    }

    /**
     * Reads {@code wire} as the game's packets do; ends in the game's lookup, so {@code getContainer()} is set. Null
     * when the game's lookup throws, as it does for a parent that is gone (a player who left), and for a bag or vehicle
     * part that is gone. Throws when the bytes are not one whole container id.
     */
    public static ContainerID parse(byte[] wire, IConnection connection) {
        ContainerKey key = keyOf(wire);
        if (key != null && !parentThere(key, wire, connection)) {
            return null;
        }
        return lookUp(wire, connection);
    }

    // the game's lookup logs an error for each of these that is gone, and a frame entry may name one that just left
    private static boolean parentThere(ContainerKey key, byte[] wire, IConnection connection) {
        return switch (key.kind()) {
            case BAG -> {
                ContainerID owner = lookUp(ContainerWire.ownerInventory(wire), connection);
                ItemContainer inventory = owner == null ? null : owner.getContainer();
                yield inventory != null && inventory.getItemWithIDRecursiv(key.sub()) instanceof InventoryContainer;
            }
            case VEHICLE, VEHICLE_BAG -> {
                BaseVehicle vehicle = VehicleManager.instance.getVehicleByID((short) key.owner());
                VehiclePart part = vehicle == null ? null : vehicle.getPartByIndex(key.index());
                ItemContainer items = part == null ? null : part.getItemContainer();
                yield items != null && (key.kind() == ContainerKey.Kind.VEHICLE
                        || items.getItemWithID(key.sub()) instanceof InventoryContainer);
            }
            default -> true;
        };
    }

    private static ContainerID lookUp(byte[] wire, IConnection connection) {
        ContainerID id = new ContainerID();
        ByteBufferReader in = new ByteBufferReader(ByteBuffer.wrap(wire));
        try {
            id.parse(in, connection);
        } catch (RuntimeException e) {
            return null;
        }
        if (in.remaining() != 0) {
            throw new IllegalStateException(in.remaining() + " bytes after a container id");
        }
        return id;
    }
}
