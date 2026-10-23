package com.dont39.pz.syncguard.protocol;

import com.dont39.pz.syncguard.protocol.Message.Hello;
import com.dont39.pz.syncguard.protocol.Message.OpenCheck;
import com.dont39.pz.syncguard.protocol.Message.Snapshot;
import com.dont39.pz.syncguard.protocol.Message.SnapshotRequest;
import com.dont39.pz.syncguard.protocol.Message.SyncFrame;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodecTest {
    private static final WireKey PLAYER = WireKey.container(new byte[] {7, 0, 12, -1});
    private static final WireKey ITEM = WireKey.item(new byte[] {3, 0, 0, 1, 2, 0, 0, 0, 4, 5}, -123456789);

    private static ByteBuffer encode(Message m) {
        ByteBuffer out = ByteBuffer.allocate(4096);
        Codec.encode(m, out);
        return out.flip();
    }

    private static <T extends Message> T roundTrip(T m, Class<T> type) {
        return assertInstanceOf(type, Codec.decode(encode(m)));
    }

    @Test
    void hello() {
        assertEquals(new Hello("0.1.0"), roundTrip(new Hello("0.1.0"), Hello.class));
    }

    @Test
    void syncFrame() {
        SyncFrame frame = new SyncFrame(42, List.of(
                new SyncFrame.Entry(PLAYER, false, 0x0123_4567_89AB_CDEFL),
                new SyncFrame.Entry(ITEM, true, 0),
                new SyncFrame.Entry(ITEM, false, -1)));
        SyncFrame read = roundTrip(frame, SyncFrame.class);
        assertEquals(frame, read);
        int size = frame.entries().stream().mapToInt(Codec::size).sum();
        assertEquals(2 + Long.BYTES + Integer.BYTES + size, encode(frame).remaining());
    }

    @Test
    void emptyFrame() {
        assertEquals(new SyncFrame(1, List.of()), roundTrip(new SyncFrame(1, List.of()), SyncFrame.class));
    }

    @Test
    void snapshotRequest() {
        SnapshotRequest r = new SnapshotRequest(List.of(PLAYER, ITEM));
        assertEquals(r, roundTrip(r, SnapshotRequest.class));
    }

    @Test
    void openCheck() {
        OpenCheck c = new OpenCheck(ITEM, Long.MIN_VALUE);
        assertEquals(c, roundTrip(c, OpenCheck.class));
    }

    @Test
    void snapshotWithItems() {
        ByteBuffer out = ByteBuffer.allocate(4096);
        SnapshotWriter.start(out, PLAYER, 9, Snapshot.State.PRESENT, 77)
                .add(b -> b.put(bytes("add")))
                .item(1, b -> b.put(bytes("fields1")), null, b -> b.put(bytes("stats1")), null)
                .item(2, b -> b.put(bytes("fields2")), b -> b.put(bytes("mod2")), b -> b.put(bytes("stats2")),
                        b -> b.put(bytes("weapon2")))
                .finish();
        Snapshot s = assertInstanceOf(Snapshot.class, Codec.decode(out.flip()));
        assertEquals(PLAYER, s.key());
        assertEquals(9, s.version());
        assertEquals(Snapshot.State.PRESENT, s.state());
        assertEquals(77, s.digest());
        assertArrayEquals(bytes("add"), s.add());
        assertEquals(2, s.items().size());
        Snapshot.Item first = s.items().get(0);
        assertEquals(1, first.id());
        assertArrayEquals(bytes("fields1"), first.fields());
        assertEquals(0, first.modData().length);
        assertArrayEquals(bytes("stats1"), first.stats());
        assertEquals(0, first.weapon().length);
        Snapshot.Item second = s.items().get(1);
        assertEquals(2, second.id());
        assertArrayEquals(bytes("mod2"), second.modData());
        assertArrayEquals(bytes("weapon2"), second.weapon());
    }

    @Test
    void goneSnapshot() {
        ByteBuffer out = ByteBuffer.allocate(256);
        SnapshotWriter.start(out, ITEM, 3, Snapshot.State.GONE, 0).add(null).finish();
        Snapshot s = assertInstanceOf(Snapshot.class, Codec.decode(out.flip()));
        assertEquals(ITEM, s.key());
        assertEquals(Snapshot.State.GONE, s.state());
        assertEquals(0, s.add().length);
        assertTrue(s.items().isEmpty());
    }

    @Test
    void snapshotWriterOrder() {
        ByteBuffer out = ByteBuffer.allocate(256);
        SnapshotWriter w = SnapshotWriter.start(out, ITEM, 3, Snapshot.State.PRESENT, 0);
        assertThrows(IllegalStateException.class, () -> w.item(1, null, null, null, null));
        assertThrows(IllegalStateException.class, w::finish);
        w.add(null);
        assertThrows(IllegalStateException.class, () -> w.add(null));
    }

    @Test
    void snapshotsHaveTheirOwnWriter() {
        Snapshot s = new Snapshot(ITEM, 1, Snapshot.State.GONE, 0, new byte[0], List.of());
        assertThrows(IllegalArgumentException.class, () -> Codec.encode(s, ByteBuffer.allocate(256)));
    }

    @Test
    void otherVersion() {
        ByteBuffer b = encode(new Hello("x"));
        b.put(0, (byte) (Protocol.VERSION + 1));
        assertTrue(assertThrows(ProtocolException.class, () -> Codec.decode(b)).getMessage()
                .contains("protocol version"));
    }

    @Test
    void unknownKind() {
        ByteBuffer b = ByteBuffer.wrap(new byte[] {Protocol.VERSION, 99});
        assertThrows(ProtocolException.class, () -> Codec.decode(b));
    }

    @Test
    void trailingBytes() {
        ByteBuffer m = encode(new OpenCheck(PLAYER, 1));
        ByteBuffer b = ByteBuffer.allocate(m.remaining() + 1).put(m).put((byte) 0).flip();
        assertTrue(assertThrows(ProtocolException.class, () -> Codec.decode(b)).getMessage().contains("after"));
    }

    @Test
    void everyTruncationIsAProtocolError() {
        ByteBuffer whole = encode(new SyncFrame(5, List.of(new SyncFrame.Entry(ITEM, false, 8))));
        for (int length = 0; length < whole.remaining(); length++) {
            ByteBuffer cut = whole.duplicate().limit(length);
            assertThrows(ProtocolException.class, () -> Codec.decode(cut), "cut at " + length);
        }
    }

    @Test
    void aHugeCountDoesNotAllocate() {
        ByteBuffer b = ByteBuffer.allocate(16).put((byte) Protocol.VERSION).put(Codec.SNAPSHOT_REQUEST)
                .putInt(Integer.MAX_VALUE).flip();
        assertThrows(ProtocolException.class, () -> Codec.decode(b));
    }

    @Test
    void wireKeysCompareByContent() {
        assertEquals(WireKey.container(new byte[] {1, 2}), WireKey.container(new byte[] {1, 2}));
        assertEquals(WireKey.container(new byte[] {1, 2}).hashCode(), WireKey.container(new byte[] {1, 2}).hashCode());
        assertFalse(WireKey.container(new byte[] {1, 2}).equals(WireKey.item(new byte[] {1, 2}, 0)));
        assertThrows(IllegalArgumentException.class, () -> WireKey.container(new byte[0]));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
