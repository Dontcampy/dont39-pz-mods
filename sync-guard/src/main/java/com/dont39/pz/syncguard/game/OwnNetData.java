package com.dont39.pz.syncguard.game;

import zombie.network.ZomboidNetData;

/**
 * A packet of the mod on the game's main-loop queues, in arrival order with the game's own. Its buffer has capacity
 * 0: the game's handler sees no type and discards it, and the pool keeps only 2048-byte buffers.
 */
public final class OwnNetData extends ZomboidNetData {
    private final byte[] payload;

    public OwnNetData(byte[] payload, long connectionGuid) {
        super(0);
        this.payload = payload;
        this.connection = connectionGuid;
        // the client processes entries at least 5 ms old in queue order: as old as the game's, so none is overtaken
        this.time = System.currentTimeMillis();
    }

    /** The bytes after the packet header. */
    public byte[] payload() {
        return payload;
    }
}
