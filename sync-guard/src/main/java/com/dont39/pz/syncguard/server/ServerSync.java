package com.dont39.pz.syncguard.server;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.game.ItemDigests;
import com.dont39.pz.syncguard.game.Net;
import com.dont39.pz.syncguard.game.PacketScope;
import com.dont39.pz.syncguard.game.Settings;
import com.dont39.pz.syncguard.protocol.Codec;
import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.ItemKey;
import com.dont39.pz.syncguard.protocol.Key;
import com.dont39.pz.syncguard.protocol.Message;
import com.dont39.pz.syncguard.protocol.Message.SyncFrame;
import com.dont39.pz.syncguard.protocol.Protocol;
import com.dont39.pz.syncguard.protocol.ProtocolException;
import com.dont39.pz.syncguard.protocol.Trailer;
import com.dont39.pz.syncguard.protocol.WireKey;
import com.dont39.pz.syncguard.stats.Timings;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.GameServer;
import zombie.network.IConnection;
import zombie.network.PacketTypes.PacketType;
import zombie.network.fields.ContainerID;
import zombie.network.packets.AddInventoryItemToContainerPacket;
import zombie.network.packets.INetworkPacket;
import zombie.network.packets.ItemStatsPacket;
import zombie.network.packets.RemoveInventoryItemFromContainerPacket;
import zombie.network.packets.ReplaceInventoryItemInContainerPacket;
import zombie.network.packets.SyncHandWeaponFieldsPacket;
import zombie.network.packets.SyncItemFieldsPacket;
import zombie.network.packets.SyncItemModDataPacket;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehicleManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * The server's side (R1, R3, R4, R6, R7): records which connection was sent or sent which in-scope object during a
 * tick, and at the tick's end sends each connection one frame with those objects' digests; answers snapshot requests
 * and open checks within a budget. Everything but {@link #incoming} runs on the server's main thread.
 */
public final class ServerSync {
    /** One object touched this tick, as the first packet that touched it addressed it. */
    record Touch(Key key, ItemContainer container, byte[] wire) {
    }

    /** A packet of the mod as the network thread took it: the bytes after the header. */
    private record Inbound(byte[] payload, long connectionGuid) {
    }

    static final int SNAPSHOTS_PER_TICK = 8;
    static final int SNAPSHOT_BYTES_PER_TICK = 64 * 1024;
    // per connection; more are refused, the client asks again at a later mismatch
    private static final int MAX_QUEUED = 1024;
    // a frame packet's entries, well within the connection's 1,000,000-byte send buffer
    private static final int FRAME_BYTES = 256 * 1024;
    private static final long SWEEP_NANOS = 1_000_000_000L;
    /**
     * Entries no frame covered for this many frames (at least 5 minutes) go: a client asks within seconds of a frame,
     * and the player's own items never leave its relevance.
     */
    private static final long STALE_FRAMES = 3000;

    private static volatile boolean running;
    private static Thread mainThread;

    // the mod's packets: filled on the network thread, taken at a main-loop pass's start, run at the next one's
    private static final Queue<Inbound> inbound = new ConcurrentLinkedQueue<>();
    private static final Queue<Inbound> due = new ConcurrentLinkedQueue<>();

    private static final Map<UdpConnection, Peer> peers = new HashMap<>();
    // each touched object's entry, computed once per tick for all connections
    private static final Map<Key, SyncFrame.Entry> digests = new HashMap<>();
    private static final Counters total = new Counters(null);
    // the end-of-tick phase since the last summary
    private static final Timings tickTimes = new Timings();
    private static long summaryNanos;
    private static long nextSweep;
    private static long nextSummary;

    // the in-scope packet the game wrote last, paired with the send that follows it
    private static INetworkPacket written;
    // the mod's own writes with the game's packet classes are not sends
    private static boolean ownWrite;

    private ServerSync() {
    }

    /** On a dedicated server, once the gate decided to open. */
    public static void start() {
        PacketScope.apply();
        long now = System.nanoTime();
        nextSweep = now + SWEEP_NANOS;
        summaryNanos = Settings.summarySeconds() * 1_000_000_000L;
        nextSummary = now + summaryNanos;
        running = true;
    }

    /** Undoes {@link #start} after a failure; the hooks are shut already. */
    public static void stop() {
        running = false;
        inbound.clear();
        due.clear();
        PacketScope.restore();
    }

    // ---- activation (R8)

    /** At ConnectionDetails.write RETURN: advertises the protocol to every joining client. */
    public static void detailsWritten(ByteBufferWriter out) {
        if (running) {
            Trailer.append(out.bb, Protocol.VERSION);
        }
    }

    /** At GameServer.disconnect HEAD. */
    public static void disconnected(UdpConnection connection) {
        Peer p = peers.remove(connection);
        if (p != null && p.active) {
            connectionSummary(p);
        }
    }

    // ---- own packets (S1)

    /**
     * At GameServer.addIncoming HEAD, on the network thread, also while the hooks are shut: true when the packet is
     * the mod's, which is queued only while the server checks.
     */
    public static boolean incoming(short id, ByteBufferReader in, UdpConnection connection) {
        if (id != Protocol.PACKET_ID) {
            return false;
        }
        if (running && Gate.INSTANCE.active()) {
            inbound.add(new Inbound(Net.payload(in), connection.getConnectedGUID()));
        }
        return true;
    }

    /**
     * At PoolCaps.updateServerCaps HEAD: the start of each pass of the server's main loop, ticking or not, before it
     * runs the game's queued packets. Runs what arrived before the previous pass started: the game ran every packet
     * that arrived before those in that pass, so a Hello comes after its client's PlayerConnect.
     */
    public static void loopStarts() {
        if (!running) {
            return;
        }
        for (Inbound m; (m = due.poll()) != null; ) {
            dispatch(m);
        }
        for (Inbound m; (m = inbound.poll()) != null; ) {
            due.add(m);
        }
    }

    private static void dispatch(Inbound own) {
        if (!running) {
            return;
        }
        UdpConnection c = GameServer.udpEngine.getActiveConnection(own.connectionGuid());
        if (c == null) {
            return;
        }
        Peer p = peers.computeIfAbsent(c, k -> new Peer(k, total));
        if (p.stopped != null) {
            return;
        }
        try {
            Message m = Codec.decode(ByteBuffer.wrap(own.payload()));
            if (m instanceof Message.Hello h) {
                hello(p, h);
            } else if (m instanceof Message.Bye) {
                bye(p);
            } else if (!p.active) {
                throw new ProtocolException("a " + m.getClass().getSimpleName() + " before its Hello");
            } else if (m instanceof Message.SnapshotRequest r) {
                request(p, r);
            } else if (m instanceof Message.OpenCheck o) {
                openCheck(p, o);
            } else {
                throw new ProtocolException("a " + m.getClass().getSimpleName() + ", which only servers send");
            }
        } catch (ProtocolException e) {
            p.active = false;
            p.stopped = e.getMessage();
            p.touched.clear();
            p.snapshots.clear();
            p.queued.clear();
            DebugType.Mod.warn(PREFIX + "%s: stopped checking its sync, as it sent %s", p.name(), e.getMessage());
        }
    }

    private static void hello(Peer p, Message.Hello h) {
        if (p.active) {
            throw new ProtocolException("a second Hello");
        }
        p.active = true;
        p.modVersion = h.modVersion();
        DebugType.Mod.println(PREFIX + "%s (connection %d): checking its sync (Sync Guard %s on the client)",
                p.name(), p.connection.getIndex(), h.modVersion());
    }

    // the client failed or left: nothing more goes to it, and what it still sends is ignored
    private static void bye(Peer p) {
        if (!p.active) {
            return;
        }
        p.active = false;
        p.stopped = "a Bye";
        p.touched.clear();
        p.snapshots.clear();
        p.queued.clear();
        DebugType.Mod.println(PREFIX + "%s (connection %d): stopped checking its sync, as Sync Guard on the client"
                + " stopped for this session", p.name(), p.connection.getIndex());
        connectionSummary(p);
        p.versions.clear();
    }

    // ---- touch recording (R3)

    /** At the RETURN of an in-scope packet class's write. */
    public static void written(INetworkPacket packet) {
        if (running && !ownWrite && Thread.currentThread() == mainThread) {
            written = packet;
        } else if (Settings.touches() && running && !ownWrite) {
            debug("write of %s on thread %s, not the main thread %s", packet.getClass().getSimpleName(),
                    Thread.currentThread().getName(), mainThread == null ? "(no tick yet)" : mainThread.getName());
        }
    }

    /** At PacketType.send HEAD: the packet written last goes to {@code connection}. Every packet passes here. */
    public static void sending(PacketType type, IConnection connection) {
        if (!running) {
            return;
        }
        if (Settings.touches() && Thread.currentThread() == mainThread) {
            sentTypes.merge(type.name(), 1, Integer::sum);
        }
        if (Thread.currentThread() != mainThread) {
            // another thread's send must not take the main thread's packet; an in-scope one is an error
            if (PacketScope.kind(type) != null && connection instanceof UdpConnection c) {
                activePeer(c);
            }
            return;
        }
        INetworkPacket packet = written;
        if (packet == null) {
            if (Settings.touches() && PacketScope.kind(type) != null) {
                debug("send of %s with no write seen before it", type.name());
            }
            return;
        }
        written = null;
        if (PacketScope.kind(type) != null && connection instanceof UdpConnection c) {
            Peer p = activePeer(c);
            if (p != null) {
                touch(p, packet);
            } else if (Settings.touches()) {
                debug("send of %s to %s, not an active connection", type.name(), c.getConnectedGUID());
            }
        }
    }

    /** At PacketType.onServerPacket HEAD: where the packet's data starts, or -1 when it is not of interest. */
    public static int receiving(PacketType type, ByteBufferReader in) {
        return running && PacketScope.kind(type) != null ? in.bb.position() : -1;
    }

    /**
     * At PacketType.onServerPacket FINALLY: the sender touched the object whether the game applied, rejected or failed
     * on it. A packet the game read is the connection's cached one; an unauthorized one it did not read.
     */
    public static void received(PacketType type, ByteBufferReader in, UdpConnection connection, int start) {
        if (start < 0) {
            return;
        }
        Peer p = activePeer(connection);
        if (p == null) {
            return;
        }
        if (in.bb.position() != start) {
            touch(p, connection.getPacket(type));
            return;
        }
        PacketScope.Kind kind = PacketScope.kind(type);
        // these start with the container; the item packets need only a login, which a peer has
        if (kind == PacketScope.Kind.REMOVE || kind == PacketScope.Kind.DELETE) {
            ByteBuffer data = in.bb.duplicate();
            data.position(start);
            ContainerID id = new ContainerID();
            try {
                id.parse(new ByteBufferReader(data), connection);
            } catch (RuntimeException e) {
                // the client's bytes, which the game would not have read either
                return;
            }
            touchContainer(p, id);
        }
    }

    private static Peer activePeer(UdpConnection c) {
        Thread t = Thread.currentThread();
        if (t != mainThread) {
            if (mainThread == null) {
                // before the first tick no connection is checked yet
                return null;
            }
            throw new IllegalStateException("an in-scope packet passed on thread " + t.getName() + ", not the"
                    + " server's main thread; this version of the mod tracks only main-thread sends");
        }
        Peer p = peers.get(c);
        return p != null && p.active ? p : null;
    }

    private static void touch(Peer p, INetworkPacket packet) {
        if (packet instanceof AddInventoryItemToContainerPacket a) {
            touchContainer(p, a.containerId);
        } else if (packet instanceof RemoveInventoryItemFromContainerPacket r) {
            touchContainer(p, r.containerId);
        } else if (packet instanceof ReplaceInventoryItemInContainerPacket r) {
            touchContainer(p, r.containerId);
        } else if (packet instanceof SyncItemFieldsPacket f) {
            touchItem(p, f.containerId, f.itemId);
        } else if (packet instanceof SyncItemModDataPacket m) {
            touchItem(p, m.containerId, m.itemId);
        } else if (packet instanceof SyncHandWeaponFieldsPacket w) {
            touchItem(p, w.containerId, w.itemId);
        } else if (packet instanceof ItemStatsPacket s) {
            touchItem(p, s.containerId, s.id);
        } else {
            throw new IllegalStateException("an in-scope packet type sent a " + packet.getClass().getName());
        }
    }

    private static void touchContainer(Peer p, ContainerID id) {
        ItemContainer container = id.getContainer();
        ContainerKey key = container != null ? Containers.keyOf(id) : null;
        if (Settings.touches()) {
            debug("touch %s %s: %s", id.getContainerType(), key, container == null ? "no container" : "recorded");
        }
        if (key != null) {
            p.touched.computeIfAbsent(key, k -> new Touch(k, container, Containers.wire(id)));
        }
    }

    private static void touchItem(Peer p, ContainerID id, int itemId) {
        ItemContainer container = id.getContainer();
        ContainerKey key = container != null ? Containers.keyOf(id) : null;
        if (Settings.touches()) {
            debug("touch item %d in %s %s: %s", itemId, id.getContainerType(), key,
                    container == null ? "no container" : "recorded");
        }
        if (key != null) {
            p.touched.computeIfAbsent(new ItemKey(key, itemId), k -> new Touch(k, container, Containers.wire(id)));
        }
    }

    private static void debug(String format, Object... args) {
        DebugType.Mod.println(PREFIX + "debug: " + format, args);
    }

    // ---- end of tick (R4)

    /** At ServerMap.postupdate HEAD: once per world tick, after all of its sends. */
    public static void endOfTick() {
        if (!running) {
            return;
        }
        long start = System.nanoTime();
        mainThread = Thread.currentThread();
        digests.clear();
        for (Peer p : peers.values()) {
            if (!p.active || !p.connection.isFullyConnected()) {
                continue;
            }
            if (!p.touched.isEmpty()) {
                sendFrame(p);
            }
            Snapshots.serve(p);
        }
        digests.clear();
        if (start >= nextSweep) {
            nextSweep = start + SWEEP_NANOS;
            sweep();
        }
        long end = System.nanoTime();
        tickTimes.record(end - start);
        if (end >= nextSummary) {
            nextSummary = end + summaryNanos;
            summary();
        }
    }

    private static void sendFrame(Peer p) {
        long version = ++p.frame;
        List<SyncFrame.Entry> entries = new ArrayList<>(p.touched.size());
        int gone = 0;
        for (Touch t : p.touched.values()) {
            SyncFrame.Entry e = digests.computeIfAbsent(t.key(), k -> resolve(t));
            entries.add(e);
            if (e.gone()) {
                gone++;
                p.versions.remove(t.key());
            } else {
                p.versions.put(t.key(), version);
            }
        }
        p.touched.clear();
        int bytes = 0;
        int from = 0;
        int size = 0;
        for (int i = 0; i < entries.size(); i++) {
            size += Codec.size(entries.get(i));
            if (size > FRAME_BYTES) {
                bytes += sendFrame(p, version, entries.subList(from, i));
                from = i;
                size = Codec.size(entries.get(i));
            }
        }
        bytes += sendFrame(p, version, entries.subList(from, entries.size()));
        p.session.frame(entries.size(), gone, bytes);
    }

    private static int sendFrame(Peer p, long version, List<SyncFrame.Entry> entries) {
        SyncFrame frame = new SyncFrame(version, entries);
        return Net.send(p.connection, Protocol.SHARED_CHANNEL, out -> Codec.encode(frame, out));
    }

    private static SyncFrame.Entry resolve(Touch t) {
        WireKey wire = t.key() instanceof ItemKey ik ? WireKey.item(t.wire(), ik.itemId()) : WireKey.container(t.wire());
        if (!Containers.live(t.container(), t.key().container())) {
            return new SyncFrame.Entry(wire, true, 0);
        }
        if (t.key() instanceof ItemKey ik) {
            InventoryItem item = t.container().getItemWithID(ik.itemId());
            return item == null ? new SyncFrame.Entry(wire, true, 0)
                    : new SyncFrame.Entry(wire, false, ItemDigests.item(item));
        }
        return new SyncFrame.Entry(wire, false, ItemDigests.container(t.container()));
    }

    // ---- requests (R6, R7)

    private static void request(Peer p, Message.SnapshotRequest r) {
        for (WireKey wire : r.keys()) {
            Key key = key(p, wire);
            boolean refused = key == null || !p.versions.containsKey(key) || p.queued.size() >= MAX_QUEUED;
            p.session.requested(refused);
            if (!refused && p.queued.add(key)) {
                p.snapshots.add(new Peer.Job(key, wire));
            }
        }
    }

    private static void openCheck(Peer p, Message.OpenCheck o) {
        ContainerID id = parse(p, o.key().containerBytes());
        Key key = id == null ? null : key(id, o.key());
        if (key == null || !relevant(p.connection, key)) {
            p.session.requested(true);
            return;
        }
        ItemContainer container = id.getContainer();
        Long digest = null;
        if (Containers.live(container, key.container())) {
            if (key instanceof ItemKey ik) {
                InventoryItem item = container.getItemWithID(ik.itemId());
                digest = item == null ? null : ItemDigests.item(item);
            } else {
                digest = ItemDigests.container(container);
            }
        }
        boolean differed = digest == null || digest != o.digest();
        p.session.check(differed);
        p.versions.putIfAbsent(key, p.frame);
        if (differed && p.queued.size() < MAX_QUEUED && p.queued.add(key)) {
            p.snapshots.add(new Peer.Job(key, o.key()));
        }
    }

    private static Key key(Peer p, WireKey wire) {
        ContainerID id = parse(p, wire.containerBytes());
        return id == null ? null : key(id, wire);
    }

    /** Null for a floor container, and for a kind of address the server never sends. */
    private static Key key(ContainerID id, WireKey wire) {
        ContainerKey ck;
        try {
            ck = Containers.keyOf(id);
        } catch (IllegalStateException e) {
            // the client named a container type the game parses but never addresses, e.g. Undefined
            return null;
        }
        if (ck == null) {
            return null;
        }
        return wire.isItem() ? new ItemKey(ck, wire.itemId()) : ck;
    }

    /**
     * A client's bytes as the game reads them, or null when the game cannot: the client's garbage, or a parent that
     * left (the game's lookup throws for a player who is gone).
     */
    static ContainerID parse(Peer p, byte[] wire) {
        try {
            return Containers.parse(wire, p.connection);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- relevance (R1)

    private static void sweep() {
        for (Iterator<Peer> it = peers.values().iterator(); it.hasNext(); ) {
            Peer p = it.next();
            if (!GameServer.udpEngine.connections.contains(p.connection)) {
                it.remove();
                if (p.active) {
                    connectionSummary(p);
                }
                continue;
            }
            long oldest = p.frame - STALE_FRAMES;
            p.versions.entrySet().removeIf(e -> e.getValue() < oldest || !relevant(p.connection, e.getKey()));
        }
    }

    /** Whether the game still sends {@code key}'s changes to {@code c}. */
    static boolean relevant(UdpConnection c, Key key) {
        ContainerKey k = key.container();
        return switch (k.kind()) {
            case PLAYER, BAG -> c.hasPlayer((short) k.owner());
            case VEHICLE, VEHICLE_BAG -> {
                BaseVehicle v = VehicleManager.instance.getVehicleByID((short) k.owner());
                yield v != null && c.isRelevantTo(v.getX(), v.getY());
            }
            case OBJECT, OBJECT_CONTAINER, CORPSE, WORLD_ITEM -> c.isRelevantTo(k.x(), k.y());
        };
    }

    // ---- summary (R9)

    /**
     * Every {@code syncguard.summarySeconds} (default 600) and per connection when it leaves: one line of
     * {@code key=value} pairs of plain numbers, which the load-test tools parse. Counters run from the server's start
     * (the connection's join); tick times cover the time since the last line.
     */
    // debug only: packets the game sent since the last summary, by type
    private static final Map<String, Integer> sentTypes = new HashMap<>();

    private static void summary() {
        if (!sentTypes.isEmpty()) {
            debug("sent since the last summary: %s", sentTypes);
            sentTypes.clear();
        }
        int connections = 0;
        int state = 0;
        for (Peer p : peers.values()) {
            if (p.active) {
                connections++;
                state += p.versions.size();
            }
        }
        Runtime rt = Runtime.getRuntime();
        StringBuilder line = new StringBuilder("scope=server");
        line.append(" connections=").append(connections).append(" stateEntries=").append(state);
        total.appendTo(line);
        line.append(" ticks=").append(tickTimes.count()).append(" tickNsP50=").append(tickTimes.percentileNanos(0.5))
                .append(" tickNsP99=").append(tickTimes.percentileNanos(0.99))
                .append(" tickNsMax=").append(tickTimes.maxNanos())
                .append(" heapUsed=").append(rt.totalMemory() - rt.freeMemory());
        DebugType.Mod.println(PREFIX + "%s", line);
        tickTimes.reset();
    }

    private static void connectionSummary(Peer p) {
        StringBuilder line = new StringBuilder("scope=connection");
        line.append(" connection=").append(p.connection.getIndex()).append(" stateEntries=")
                .append(p.versions.size());
        p.session.appendTo(line);
        DebugType.Mod.println(PREFIX + "%s", line);
    }

    static void ownWrite(boolean on) {
        ownWrite = on;
    }
}
