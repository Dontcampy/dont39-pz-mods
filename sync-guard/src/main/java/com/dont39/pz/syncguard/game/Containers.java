package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.ContainerWire;
import com.dont39.pz.syncguard.protocol.ProtocolException;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoObject;
import zombie.network.IConnection;
import zombie.network.fields.ContainerID;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;
import zombie.vehicles.VehiclePart;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Containers as the game addresses them on the wire ({@code ContainerID}), the mod's nested address of bags the game
 * cannot address, and the mod's keys for them.
 */
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

    /** The key of the mod's bytes {@code wire}; null for a floor container. */
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

    /** A container's key and the mod's bytes for it. */
    public record Address(ContainerKey key, byte[] wire) {
    }

    /** A container found from the mod's bytes, and the vehicle part whose items it holds, if any. */
    public record Resolved(ItemContainer container, VehiclePart part) {
    }

    /**
     * The mod's address of {@code container} as it sits now, without a lookup: the game's own, or a nested one for a
     * bag the game's ids cannot address. Null for a floor container, one out of the world, and a bag whose outer
     * container has no address or that is more than {@link ContainerKey#MAX_BAGS} bags deep.
     */
    public static Address current(ItemContainer container) {
        if (unaddressedBag(container)) {
            return nested(container);
        }
        ContainerID id = locate(container);
        return id == null ? null : address(id, keyOf(id));
    }

    /** The address of the container a game id was set to: the id's own, or the nested one of a bag it cannot name. */
    public static Address of(ContainerID id) {
        ItemContainer container = id.getContainer();
        return container != null && unaddressedBag(container) ? nested(container) : address(id, keyOf(id));
    }

    private static Address address(ContainerID id, ContainerKey key) {
        return key == null ? null : new Address(key, wire(id));
    }

    /**
     * Where a client finds {@code container}, or null when the lookup of its address would not end at this very
     * container: a floor container, one not in the world, an object no longer on its square, a bag whose outer
     * container has no address. Server only: resolves players by online id, as a client does.
     */
    public static Address address(ItemContainer container) {
        Address at = current(container);
        if (at == null) {
            return null;
        }
        ContainerKey key = at.key();
        // indexOf of an object no longer on its square; the game's lookup would log an error for it
        if (key.kind().square() && key.index() < 0) {
            return null;
        }
        Resolved back = find(at.wire(), null);
        return back != null && back.container() == container ? at : null;
    }

    /**
     * Whether {@code container} is still the one addressed as {@code key}: not taken out of the world, not at another
     * object index or in another bag, not a stand-in the game's lookup made up for an index out of range.
     */
    public static boolean live(ItemContainer container, ContainerKey key) {
        if (container == null) {
            return false;
        }
        Address now = current(container);
        return now != null && key.equals(now.key());
    }

    /**
     * Finds the container the mod's bytes address: the game's own lookup, then the bags from the anchor for a nested
     * address. Null when the game's lookup throws, as it does for a parent that is gone (a player who left), and for a
     * bag, vehicle part or nested bag that is gone. Throws when the bytes are not one whole address.
     */
    public static Resolved find(byte[] wire, IConnection connection) {
        ContainerKey key = keyOf(wire);
        if (key != null && key.kind() == ContainerKey.Kind.NESTED) {
            return inBags(key, ContainerWire.anchor(wire), connection);
        }
        if (key != null && !parentThere(key, wire, connection)) {
            return null;
        }
        ContainerID id = lookUp(wire, connection);
        return id == null ? null : new Resolved(id.getContainer(), id.getPart());
    }

    private static Resolved inBags(ContainerKey key, byte[] anchor, IConnection connection) {
        Resolved outer = find(anchor, connection);
        ItemContainer at = outer == null ? null : outer.container();
        // not a stand-in the game's lookup made up for an index out of range
        if (!live(at, key.anchor())) {
            return null;
        }
        for (int bagId : key.bags()) {
            if (!(at.getItemWithID(bagId) instanceof InventoryContainer bag) || (at = bag.getItemContainer()) == null) {
                return null;
            }
        }
        return new Resolved(at, null);
    }

    /**
     * Whether the game's ids cannot address {@code container}: a bag in a world, corpse or floor-bag container at any
     * depth, which ContainerID.set names as the outer container, and a bag below the first level of a vehicle part,
     * which ContainerID.findObject does not search. Bags in a player's inventory and on the floor they address.
     */
    private static boolean unaddressedBag(ItemContainer container) {
        InventoryItem bag = container.getContainingItem();
        if (bag == null || bag.getWorldItem() != null) {
            return false;
        }
        ItemContainer outer = bag.getOutermostContainer();
        // in a floor container, which the game rebuilds from the squares on each side
        if (outer == null) {
            return false;
        }
        IsoObject root = outer.getParent();
        return !(root instanceof IsoPlayer) && !(root instanceof BaseVehicle && bag.getContainer() == outer);
    }

    // the bags from the first container the game addresses itself (a bag on the floor counts) down to this one
    private static Address nested(ItemContainer container) {
        List<Integer> bags = new ArrayList<>();
        ItemContainer at = container;
        for (InventoryItem bag = at.getContainingItem(); bag != null && bag.getWorldItem() == null;
                bag = at.getContainingItem()) {
            if (bags.size() == ContainerKey.MAX_BAGS || bag.getContainer() == null) {
                return null;
            }
            bags.add(0, bag.getID());
            at = bag.getContainer();
        }
        ContainerID id = locate(at);
        ContainerKey anchor = id == null ? null : keyOf(id);
        if (anchor == null || !anchor.kind().anchors() || anchor.kind().square() && anchor.index() < 0) {
            return null;
        }
        return new Address(ContainerKey.nested(anchor, bags), ContainerWire.nested(wire(id), bags));
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
