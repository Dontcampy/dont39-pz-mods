package com.dont39.pz.syncguard.protocol;

import com.dont39.pz.syncguard.protocol.Message.Hello;
import com.dont39.pz.syncguard.protocol.Message.OpenCheck;
import com.dont39.pz.syncguard.protocol.Message.Snapshot;
import com.dont39.pz.syncguard.protocol.Message.SnapshotRequest;
import com.dont39.pz.syncguard.protocol.Message.SyncFrame;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The messages' bytes after the packet header: {@code [byte protocol version][byte kind][body]}, big-endian. Snapshots
 * are written with {@link SnapshotWriter}, which streams the game's payloads into the buffer.
 */
public final class Codec {
    static final byte HELLO = 1;
    static final byte SYNC_FRAME = 2;
    static final byte SNAPSHOT_REQUEST = 3;
    static final byte SNAPSHOT = 4;
    static final byte OPEN_CHECK = 5;

    private static final int MAX_STRING = 256;

    private Codec() {
    }

    public static void encode(Message message, ByteBuffer out) {
        if (message instanceof Hello h) {
            header(out, HELLO);
            byte[] text = h.modVersion().getBytes(StandardCharsets.UTF_8);
            if (text.length > MAX_STRING) {
                throw new IllegalArgumentException("a mod version of " + text.length + " bytes");
            }
            out.putShort((short) text.length).put(text);
        } else if (message instanceof SyncFrame f) {
            header(out, SYNC_FRAME);
            out.putLong(f.version()).putInt(f.entries().size());
            for (SyncFrame.Entry e : f.entries()) {
                e.key().write(out);
                out.put((byte) (e.gone() ? 1 : 0));
                if (!e.gone()) {
                    out.putLong(e.digest());
                }
            }
        } else if (message instanceof SnapshotRequest r) {
            header(out, SNAPSHOT_REQUEST);
            out.putInt(r.keys().size());
            r.keys().forEach(k -> k.write(out));
        } else if (message instanceof OpenCheck c) {
            header(out, OPEN_CHECK);
            c.key().write(out);
            out.putLong(c.digest());
        } else {
            throw new IllegalArgumentException("snapshots are written with SnapshotWriter");
        }
    }

    /** Bytes {@code entry} takes in a frame, to split frames by size. */
    public static int size(SyncFrame.Entry entry) {
        return entry.key().size() + 1 + (entry.gone() ? 0 : Long.BYTES);
    }

    static void header(ByteBuffer out, byte kind) {
        out.put((byte) Protocol.VERSION).put(kind);
    }

    /** Reads all of {@code in}; throws {@link ProtocolException} for anything that is not one whole message. */
    public static Message decode(ByteBuffer in) {
        try {
            int version = in.get() & 0xFF;
            if (version != Protocol.VERSION) {
                throw new ProtocolException("protocol version " + version + ", this version of the mod speaks "
                        + Protocol.VERSION);
            }
            byte kind = in.get();
            Message m = switch (kind) {
                case HELLO -> new Hello(string(in));
                case SYNC_FRAME -> frame(in);
                case SNAPSHOT_REQUEST -> request(in);
                case SNAPSHOT -> snapshot(in);
                case OPEN_CHECK -> new OpenCheck(WireKey.read(in), in.getLong());
                default -> throw new ProtocolException("unknown message kind " + kind);
            };
            if (in.hasRemaining()) {
                throw new ProtocolException(in.remaining() + " bytes after a whole " + m.getClass().getSimpleName());
            }
            return m;
        } catch (BufferUnderflowException e) {
            throw new ProtocolException("a message ends early", e);
        }
    }

    private static String string(ByteBuffer in) {
        int length = in.getShort() & 0xFFFF;
        if (length > MAX_STRING) {
            throw new ProtocolException("a string of " + length + " bytes");
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static SyncFrame frame(ByteBuffer in) {
        long version = in.getLong();
        int count = count(in, 4);
        List<SyncFrame.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            WireKey key = WireKey.read(in);
            boolean gone = flag(in);
            entries.add(new SyncFrame.Entry(key, gone, gone ? 0 : in.getLong()));
        }
        return new SyncFrame(version, entries);
    }

    private static SnapshotRequest request(ByteBuffer in) {
        int count = count(in, 4);
        List<WireKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(WireKey.read(in));
        }
        return new SnapshotRequest(keys);
    }

    private static Snapshot snapshot(ByteBuffer in) {
        WireKey key = WireKey.read(in);
        long version = in.getLong();
        int state = in.get();
        Snapshot.State[] states = Snapshot.State.values();
        if (state < 0 || state >= states.length) {
            throw new ProtocolException("unknown snapshot state " + state);
        }
        long digest = in.getLong();
        byte[] add = blob(in);
        int count = count(in, Integer.BYTES * 5);
        List<Snapshot.Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new Snapshot.Item(in.getInt(), blob(in), blob(in), blob(in), blob(in)));
        }
        return new Snapshot(key, version, states[state], digest, add, items);
    }

    private static boolean flag(ByteBuffer in) {
        return switch (in.get()) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new ProtocolException("a flag is neither 0 nor 1");
        };
    }

    /** A count whose elements take at least {@code minSize} bytes each, so a bad one cannot allocate much. */
    private static int count(ByteBuffer in, int minSize) {
        int count = in.getInt();
        if (count < 0 || (long) count * minSize > in.remaining()) {
            throw new ProtocolException("a count of " + count + " with " + in.remaining() + " bytes left");
        }
        return count;
    }

    static byte[] blob(ByteBuffer in) {
        int length = in.getInt();
        if (length < 0 || length > in.remaining()) {
            throw new ProtocolException("a payload of " + length + " bytes with " + in.remaining() + " left");
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return bytes;
    }
}
