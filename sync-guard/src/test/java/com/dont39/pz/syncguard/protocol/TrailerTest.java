package com.dont39.pz.syncguard.protocol;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrailerTest {
    private static final byte[] DETAILS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
    // mod sync's record: [byte 1][short port][int 3][long magic]
    private static final byte[] MOD_SYNC = ByteBuffer.allocate(15).put((byte) 1).putShort((short) 16262).putInt(3)
            .putLong(Trailer.MOD_SYNC_MAGIC).array();

    private static ByteBuffer written(byte[]... parts) {
        ByteBuffer b = ByteBuffer.allocate(1024);
        for (byte[] p : parts) {
            b.put(p);
        }
        return b;
    }

    /** As the client's copy: limit at the end of the data. */
    private static ByteBuffer received(ByteBuffer written) {
        return ByteBuffer.wrap(written.array(), 0, written.position()).slice();
    }

    private static int offered(ByteBuffer data) {
        return assertInstanceOf(Trailer.Decoded.Offered.class, Trailer.decode(data)).protocol();
    }

    @Test
    void roundTrip() {
        ByteBuffer out = written(DETAILS);
        Trailer.append(out, 7);
        ByteBuffer in = received(out);
        assertEquals(7, offered(in));
        assertEquals(0, in.position());
        assertEquals(DETAILS.length + 14, in.limit());
        byte[] head = new byte[DETAILS.length];
        in.get(0, head);
        assertArrayEquals(DETAILS, head);
    }

    @Test
    void vanillaServer() {
        Trailer.Decoded d = Trailer.decode(received(written(DETAILS)));
        assertTrue(assertInstanceOf(Trailer.Decoded.None.class, d).reason().contains("does not run Sync Guard"));
    }

    @Test
    void shortData() {
        assertInstanceOf(Trailer.Decoded.None.class, Trailer.decode(ByteBuffer.allocate(3)));
    }

    @Test
    void goesBeforeModSyncsRecordSoModSyncStillFindsItsOwnAtTheEnd() {
        ByteBuffer out = written(DETAILS, MOD_SYNC);
        Trailer.append(out, 1);
        ByteBuffer in = received(out);
        byte[] tail = new byte[MOD_SYNC.length];
        in.get(in.limit() - MOD_SYNC.length, tail);
        assertArrayEquals(MOD_SYNC, tail);
        assertEquals(1, offered(in));
    }

    @Test
    void stepsOverModSyncsRecordAppendedAfterIt() {
        ByteBuffer out = written(DETAILS);
        Trailer.append(out, 1);
        out.put(MOD_SYNC);
        assertEquals(1, offered(received(out)));
    }

    @Test
    void otherTrailerVersion() {
        ByteBuffer out = written(DETAILS);
        Trailer.append(out, 1);
        out.put(DETAILS.length, (byte) (Trailer.TRAILER_VERSION + 1));
        Trailer.Decoded d = Trailer.decode(received(out));
        assertTrue(assertInstanceOf(Trailer.Decoded.None.class, d).reason().contains("version"));
    }

    @Test
    void lengthThatDoesNotFit() {
        ByteBuffer b = ByteBuffer.allocate(14).put((byte) 1).put((byte) 1).putInt(1000).putLong(Trailer.MAGIC).flip();
        assertInstanceOf(Trailer.Decoded.None.class, Trailer.decode(b));
    }

    @Test
    void decodeLeavesTheBuffer() {
        ByteBuffer out = written(DETAILS);
        Trailer.append(out, 1);
        ByteBuffer in = received(out);
        in.position(5);
        Trailer.decode(in);
        assertEquals(5, in.position());
    }
}
