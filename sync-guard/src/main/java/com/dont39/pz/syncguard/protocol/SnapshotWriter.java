package com.dont39.pz.syncguard.protocol;

import com.dont39.pz.syncguard.protocol.Message.Snapshot;

import java.nio.ByteBuffer;

/**
 * Writes a {@link Snapshot} straight into a buffer, so the game's packet classes write their payloads in place:
 * {@link #start}, {@link #add} once, {@link #item} per item, {@link #finish}.
 */
public final class SnapshotWriter {
    /** Writes one payload at the buffer's position. */
    @FunctionalInterface
    public interface Payload {
        void write(ByteBuffer out);
    }

    private final ByteBuffer out;
    private int countAt = -1;
    private int count;

    private SnapshotWriter(ByteBuffer out) {
        this.out = out;
    }

    public static SnapshotWriter start(ByteBuffer out, WireKey key, long version, Snapshot.State state, long digest) {
        Codec.header(out, Codec.SNAPSHOT);
        key.write(out);
        out.putLong(version).put((byte) state.ordinal()).putLong(digest);
        return new SnapshotWriter(out);
    }

    /** The items' AddInventoryItemToContainer payload; null for none. */
    public SnapshotWriter add(Payload add) {
        if (countAt >= 0) {
            throw new IllegalStateException("add was written already");
        }
        blob(add);
        countAt = out.position();
        out.putInt(0);
        return this;
    }

    /** {@code digest}: the item's digest on the server. Null for a payload not sent. */
    public SnapshotWriter item(int id, long digest, Payload fields, Payload modData, Payload stats, Payload weapon) {
        if (countAt < 0) {
            throw new IllegalStateException("items come after add");
        }
        out.putInt(id).putLong(digest);
        blob(fields);
        blob(modData);
        blob(stats);
        blob(weapon);
        count++;
        return this;
    }

    public void finish() {
        if (countAt < 0) {
            throw new IllegalStateException("a snapshot without add");
        }
        out.putInt(countAt, count);
    }

    private void blob(Payload payload) {
        int at = out.position();
        out.putInt(0);
        if (payload != null) {
            payload.write(out);
            out.putInt(at, out.position() - at - Integer.BYTES);
        }
    }
}
