package com.dont39.pz.syncguard.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * The mod's key of a container id as the game's {@code ContainerID.write} writes it: read from the bytes, as the
 * fields that hold it are package-private.
 */
public final class ContainerWire {
    /** {@code ContainerID.ContainerType}'s constants in ordinal order, as this version of the mod reads them. */
    public static final List<String> TYPES = List.of("Undefined", "DeadBody", "WorldObject", "IsoObject",
            "ObjectContainer", "ObjectInVehicle", "Vehicle", "PlayerInventory", "InventoryContainer", "Floor");

    private static final int UNDEFINED = 0;
    private static final int DEAD_BODY = 1;
    private static final int WORLD_OBJECT = 2;
    private static final int ISO_OBJECT = 3;
    private static final int OBJECT_CONTAINER = 4;
    private static final int OBJECT_IN_VEHICLE = 5;
    private static final int VEHICLE = 6;
    private static final int PLAYER_INVENTORY = 7;
    private static final int INVENTORY_CONTAINER = 8;
    private static final int FLOOR = 9;

    private ContainerWire() {
    }

    /**
     * The key of the container {@code wire} addresses, or null for a floor container: the game builds a throwaway one
     * from the square's items on each side. Throws {@link ProtocolException} for bytes that are not one whole
     * container id of a type the game addresses.
     */
    public static ContainerKey decode(byte[] wire) {
        ByteBuffer in = ByteBuffer.wrap(wire);
        try {
            ContainerKey key = read(in);
            if (in.hasRemaining()) {
                throw new ProtocolException(in.remaining() + " bytes after a container id");
            }
            return key;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("a container id ends early", e);
        }
    }

    /**
     * The id of the inventory that holds the bag {@code bagWire} addresses, so the owner is found without the game's
     * bag lookup. Throws {@link ProtocolException} for bytes that are not a bag in a player inventory.
     */
    public static byte[] ownerInventory(byte[] bagWire) {
        ByteBuffer in = ByteBuffer.wrap(bagWire);
        try {
            if ((in.get() & 0xFF) != INVENTORY_CONTAINER) {
                throw new ProtocolException("not a bag in a player inventory");
            }
            ByteBuffer out = ByteBuffer.allocate(1 + Short.BYTES + 1).put((byte) PLAYER_INVENTORY);
            out.putShort(in.getShort()).put(in.get());
            return out.array();
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("a container id ends early", e);
        }
    }

    private static ContainerKey read(ByteBuffer in) {
        int type = in.get() & 0xFF;
        switch (type) {
            case PLAYER_INVENTORY -> {
                int player = playerId(in);
                return ContainerKey.player(player);
            }
            case INVENTORY_CONTAINER -> {
                int player = playerId(in);
                return ContainerKey.bag(player, in.getInt());
            }
            case UNDEFINED -> throw new ProtocolException("a container id of type Undefined");
            default -> {
                if (type >= TYPES.size()) {
                    throw new ProtocolException("a container id of type " + type + ", which this version of the mod"
                            + " does not know");
                }
            }
        }
        int x = in.getInt();
        int y = in.getInt();
        int z = in.get();
        return switch (type) {
            case DEAD_BODY -> ContainerKey.corpse(x, y, z, in.getShort());
            case WORLD_OBJECT -> ContainerKey.worldItem(x, y, z, in.getInt());
            case ISO_OBJECT -> ContainerKey.object(x, y, z, in.getShort());
            case OBJECT_CONTAINER -> {
                int index = in.getShort();
                yield ContainerKey.objectContainer(x, y, z, index, in.getShort());
            }
            // a vehicle's square is where it was when written; the key names it by id only
            case VEHICLE -> {
                int vehicle = in.getShort();
                yield ContainerKey.vehicle(vehicle, in.getShort());
            }
            case OBJECT_IN_VEHICLE -> {
                int vehicle = in.getShort();
                int part = in.getShort();
                yield ContainerKey.vehicleBag(vehicle, part, in.getInt());
            }
            case FLOOR -> {
                // (x, y) pairs; the game reads none for a count of 0 or less
                int squares = in.get();
                if (squares > 0) {
                    in.get(new byte[squares * 2 * Integer.BYTES]);
                }
                yield null;
            }
            default -> throw new IllegalStateException("type " + type);
        };
    }

    // PlayerID: the online id, then the player index the key leaves out
    private static int playerId(ByteBuffer in) {
        short id = in.getShort();
        in.get();
        return id;
    }
}
