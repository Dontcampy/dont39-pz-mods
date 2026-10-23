package com.dont39.pz.syncguard.game;

import com.dont39.pz.syncguard.protocol.Protocol;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** The mod's raw packets: header 134 plus {@link Protocol#PACKET_ID}, then a {@code Codec} message. */
public final class Net {
    private Net() {
    }

    /**
     * Sends one packet on {@code connection}, RELIABLE_ORDERED on {@code channel}; returns its size in bytes. Only
     * from the mod's own message handlers and the end-of-tick hook: inside a game send of the same thread the
     * connection's lock is held already and is reentrant, so this would overwrite the game's half-written packet.
     * Throws instead of doing that.
     */
    public static int send(UdpConnection connection, byte channel, Consumer<ByteBuffer> message) {
        Lock lock = connection.bufferLock;
        if (!(lock instanceof ReentrantLock reentrant)) {
            throw new IllegalStateException("the game's connection lock is a " + lock.getClass().getName()
                    + ", not the ReentrantLock this version of the mod knows");
        }
        if (reentrant.isHeldByCurrentThread()) {
            throw new IllegalStateException("a send of the mod inside one of the game's on the same connection");
        }
        ByteBufferWriter out = connection.startPacket();
        try {
            out.putByte(Protocol.USER_PACKET);
            out.putShort(Protocol.PACKET_ID);
            message.accept(out.bb);
            int size = out.position();
            connection.endPacket(Protocol.PRIORITY_HIGH, Protocol.RELIABLE_ORDERED, channel);
            return size;
        } catch (Throwable t) {
            connection.cancelPacket();
            throw t;
        }
    }

    /** Whether {@link #send} may run now: not inside one of the game's sends on {@code connection} on this thread. */
    public static boolean canSend(UdpConnection connection) {
        return !(connection.bufferLock instanceof ReentrantLock reentrant && reentrant.isHeldByCurrentThread());
    }

    /** The payload after the 3-byte header the game already read; leaves {@code in} as it is. */
    public static byte[] payload(ByteBufferReader in) {
        ByteBuffer b = in.bb.duplicate();
        byte[] payload = new byte[b.remaining()];
        b.get(payload);
        return payload;
    }
}
