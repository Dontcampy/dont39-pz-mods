package com.dont39.pz.syncguard.protocol;

import java.nio.ByteBuffer;

/**
 * The record a server with the mod appends to the connection-details it writes, {@code [payload][int length][long
 * MAGIC]}, as Dont39 All-in-One's mod sync does with its own. Vanilla clients stop reading before it. Payload version 1
 * is {@code [byte TRAILER_VERSION][byte protocol version]}.
 * <p>
 * Mod sync reads only the record at the very end, so ours goes before a mod sync record already there, and readers
 * here step over one.
 */
public final class Trailer {
    /** "D39SYNCG". */
    public static final long MAGIC = 0x4433_3953_594E_4347L;
    /** Mod sync's "D39MSYNC". */
    static final long MOD_SYNC_MAGIC = 0x4433_394D_5359_4E43L;
    public static final int TRAILER_VERSION = 1;

    private static final int FOOTER = Integer.BYTES + Long.BYTES;
    private static final int V1_SIZE = 2;

    /** What a client read: the server's protocol version, or why there is none. */
    public sealed interface Decoded {
        record Offered(int protocol) implements Decoded {
        }

        record None(String reason) implements Decoded {
        }
    }

    private Trailer() {
    }

    /** Appends to the data in {@code [0, position)} of {@code out}, before a mod sync record at its end. */
    public static void append(ByteBuffer out, int protocol) {
        // a duplicate is big-endian, as the game writes
        ByteBuffer b = out.duplicate();
        int end = b.position();
        int start = recordStart(b, end, MOD_SYNC_MAGIC);
        byte[] after = new byte[end - start];
        b.get(start, after);
        b.position(start);
        b.put((byte) TRAILER_VERSION).put((byte) protocol).putInt(V1_SIZE).putLong(MAGIC);
        b.put(after);
        out.position(b.position());
    }

    /** Reads the data in {@code [0, limit)} of {@code data}; leaves its position and limit as they are. */
    public static Decoded decode(ByteBuffer data) {
        ByteBuffer in = data.duplicate();
        int end = in.limit();
        end = recordStart(in, end, MOD_SYNC_MAGIC);
        if (end < FOOTER || in.getLong(end - Long.BYTES) != MAGIC) {
            return new Decoded.None("the server does not run Sync Guard");
        }
        int length = in.getInt(end - FOOTER);
        int start = end - FOOTER - length;
        if (length < 1 || start < 0) {
            return new Decoded.None("the server's Sync Guard record says it has " + length + " bytes, which do not"
                    + " fit");
        }
        int version = in.get(start) & 0xFF;
        if (version != TRAILER_VERSION) {
            return new Decoded.None("the server's Sync Guard record has version " + version + ", this version of the"
                    + " mod reads " + TRAILER_VERSION + "; update Sync Guard on the side that is older");
        }
        if (length != V1_SIZE) {
            return new Decoded.None("the server's Sync Guard record has " + length + " bytes instead of " + V1_SIZE);
        }
        return new Decoded.Offered(in.get(start + 1) & 0xFF);
    }

    /** Where a whole record with {@code magic} that ends at {@code end} starts, or {@code end} if there is none. */
    private static int recordStart(ByteBuffer b, int end, long magic) {
        if (end < FOOTER || b.getLong(end - Long.BYTES) != magic) {
            return end;
        }
        int length = b.getInt(end - FOOTER);
        int start = end - FOOTER - length;
        return length < 0 || start < 0 ? end : start;
    }
}
