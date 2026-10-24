package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.ContainerWire;
import com.dont39.pz.syncguard.protocol.ProtocolException;
import zombie.core.network.ByteBufferReader;
import zombie.inventory.ItemContainer;
import zombie.network.IConnection;
import zombie.network.fields.ContainerID;

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
     * The key of the container {@code id} addresses, or null for a floor container: the game builds a throwaway one
     * from the square's items on each side, and square object lists are out of scope.
     */
    public static ContainerKey keyOf(ContainerID id) {
        ContainerID.ContainerType type = id.getContainerType();
        if (type == null) {
            throw new IllegalStateException("a ContainerID without a type");
        }
        // a floor id lists squares, which wire() has no room for
        return type == ContainerID.ContainerType.Floor ? null : keyOf(wire(id));
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
     * when the game's lookup throws, as it does for a parent that is gone (a player who left). Throws when the bytes
     * are not one whole container id.
     */
    public static ContainerID parse(byte[] wire, IConnection connection) {
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
