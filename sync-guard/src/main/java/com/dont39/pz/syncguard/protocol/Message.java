package com.dont39.pz.syncguard.protocol;

import java.util.List;

/** The mod's messages; {@link Codec} reads and writes them. */
public sealed interface Message {
    /** Client to server, once per join after PlayerConnect: the client checks and wants frames. */
    record Hello(String modVersion) implements Message {
    }

    /**
     * Server to client, at the end of a world tick: the touched objects' state as the server has it, with the
     * frame's number as every entry's version.
     */
    record SyncFrame(long version, List<Entry> entries) implements Message {
        /**
         * {@code gone}: the server no longer finds the object there; {@code digest} is then 0. {@code changed}: its
         * contents changed on the server this tick, by whatever route reached the clients (D7); the client compares
         * it only after a grace period, as a mod's own sync of the change may arrive after the frame.
         */
        public record Entry(WireKey key, boolean gone, long digest, boolean changed) {
            public Entry(WireKey key, boolean gone, long digest) {
                this(key, gone, digest, false);
            }

            public Entry asChanged() {
                return changed ? this : new Entry(key, gone, digest, true);
            }
        }
    }

    /** Client to server: the objects whose digest differed. */
    record SnapshotRequest(List<WireKey> keys) implements Message {
    }

    /**
     * Server to client: an object's full state. {@code add} is the game's AddInventoryItemToContainer payload of the
     * items, and each item carries the game's field packets' payloads; an empty array stands for one not sent.
     */
    record Snapshot(WireKey key, long version, State state, long digest, byte[] add, List<Item> items)
            implements Message {
        public enum State {
            PRESENT,
            /** The server no longer finds the object. */
            GONE,
            /** The object does not fit into one packet; it is not sent. */
            TOO_LARGE
        }

        /** {@code digest}: the server's item digest, to tell which items a snapshot could not make equal. */
        public record Item(int id, long digest, byte[] fields, byte[] modData, byte[] stats, byte[] weapon) {
        }
    }

    /** Client to server, when the player opens a container: the client's digest of it. */
    record OpenCheck(WireKey key, long digest) implements Message {
    }

    /** Client to server: the client stopped checking for this session (it failed, or the player left). */
    record Bye() implements Message {
    }
}
