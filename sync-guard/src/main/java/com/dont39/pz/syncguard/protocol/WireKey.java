package com.dont39.pz.syncguard.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * A key as sent: the container as the game's {@code ContainerID.write} writes it, so the receiver resolves it with
 * the game's own {@code parse}, and the item id for an item.
 */
public final class WireKey {
    static final int MAX_CONTAINER_BYTES = 0xFFFF;

    private final byte[] container;
    private final boolean item;
    private final int itemId;

    private WireKey(byte[] container, boolean item, int itemId) {
        if (container.length == 0 || container.length > MAX_CONTAINER_BYTES) {
            throw new IllegalArgumentException("a container id of " + container.length + " bytes");
        }
        this.container = container;
        this.item = item;
        this.itemId = itemId;
    }

    public static WireKey container(byte[] container) {
        return new WireKey(container, false, 0);
    }

    public static WireKey item(byte[] container, int itemId) {
        return new WireKey(container, true, itemId);
    }

    /** The game's bytes; not copied, do not change them. */
    public byte[] containerBytes() {
        return container;
    }

    public boolean isItem() {
        return item;
    }

    public int itemId() {
        return itemId;
    }

    void write(ByteBuffer out) {
        out.putShort((short) container.length);
        out.put(container);
        out.put((byte) (item ? 1 : 0));
        if (item) {
            out.putInt(itemId);
        }
    }

    static WireKey read(ByteBuffer in) {
        int length = in.getShort() & MAX_CONTAINER_BYTES;
        if (length == 0 || length > in.remaining()) {
            throw new ProtocolException("a key says its container has " + length + " bytes, " + in.remaining()
                    + " remain");
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return switch (in.get()) {
            case 0 -> container(bytes);
            case 1 -> item(bytes, in.getInt());
            default -> throw new ProtocolException("a key is neither a container nor an item");
        };
    }

    int size() {
        return Short.BYTES + container.length + 1 + (item ? Integer.BYTES : 0);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WireKey k && item == k.item && itemId == k.itemId && Arrays.equals(container, k.container);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(container) + Boolean.hashCode(item)) + itemId;
    }

    @Override
    public String toString() {
        return (item ? "item " + itemId + " in " : "") + "container " + Arrays.toString(container);
    }
}
