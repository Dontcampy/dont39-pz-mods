package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.Containers;
import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.game.ItemDigests;
import com.dont39.pz.syncguard.game.Net;
import com.dont39.pz.syncguard.game.PacketScope;
import com.dont39.pz.syncguard.game.Settings;
import com.dont39.pz.syncguard.protocol.Codec;
import com.dont39.pz.syncguard.protocol.ContainerKey;
import com.dont39.pz.syncguard.protocol.Message;
import com.dont39.pz.syncguard.protocol.Message.Snapshot;
import com.dont39.pz.syncguard.protocol.Message.SyncFrame;
import com.dont39.pz.syncguard.protocol.Protocol;
import com.dont39.pz.syncguard.protocol.Trailer;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.characters.HaloTextHelper;
import zombie.characters.IsoPlayer;
import zombie.core.Translator;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.gameStates.ConnectToServerState;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.network.GameClient;
import zombie.network.PacketTypes.PacketType;
import zombie.network.ZomboidNetData;
import zombie.network.fields.ContainerID;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * The client's side: turns on for a join whose server advertised the protocol (R8), checks the server's sync frames
 * against its own state and asks for snapshots of what differs (R5), applies them in place (R6), checks containers
 * the player opens (R7), and tells the player and the log about repairs (R9). Main thread, except {@link #incoming}.
 */
public final class ClientSync {
    private static final String NOTICE = "UI_Dont39SyncGuard_Repaired";
    // ids listed per repair line
    private static final int MAX_IDS = 20;

    // this join's: whether the server offers our protocol, and whether the client said Hello
    private static boolean active;
    private static Thread mainThread;
    // a failure off the main thread or inside a game send: the next frame says Bye
    private static volatile boolean byePending;
    // the game's queued packets that are the mod's; ZomboidNetData keeps Object's identity equals
    private static final Set<ZomboidNetData> own = ConcurrentHashMap.newKeySet();

    private static Ledger ledger = new Ledger();
    private static ClientCounters counters = new ClientCounters();
    private static Notices notices = new Notices();
    private static FieldDiff diff;
    private static long nextSummary;

    /** What one frame or retry pass asks the server. */
    private static final class Asks {
        final List<WireKey> snapshots = new ArrayList<>();
        final List<Message.OpenCheck> checks = new ArrayList<>();

        void send() {
            if (!snapshots.isEmpty()) {
                ClientSync.send(new Message.SnapshotRequest(snapshots));
            }
            checks.forEach(ClientSync::send);
        }
    }

    private ClientSync() {
    }

    /**
     * At GameClient.sendPlayerConnect RETURN: PlayerConnect is out, so a Hello on its channel follows it. The trailer
     * is read here, not where the game parses the connection-details (a client that enables the mod only with the
     * server's mod list loads it inside the join, after that) nor at ConnectToServerState.exit (the main screen calls
     * it only while it still updates, which ends once the world loads).
     */
    public static void playerConnectSent() {
        if (active) {
            return;
        }
        reset();
        String target = GameClient.ip + ":" + GameClient.port;
        // set by each join's enter()
        ConnectToServerState join = ConnectToServerState.instance;
        if (join == null) {
            DebugType.Mod.println(PREFIX + "join to %s: inactive, as the game kept no connection-details", target);
            return;
        }
        // the join's copy is exactly as long as the data, as the game receives connection-details in parts
        Trailer.Decoded decoded = Trailer.decode(join.connectionDetails.bb);
        if (decoded instanceof Trailer.Decoded.None none) {
            DebugType.Mod.println(PREFIX + "join to %s: inactive, as %s", target, none.reason());
            return;
        }
        int protocol = ((Trailer.Decoded.Offered) decoded).protocol();
        if (protocol != Protocol.VERSION) {
            DebugType.Mod.println(PREFIX + "join to %s: inactive, as the server speaks protocol %d and this version of"
                    + " the mod %d; update Sync Guard on the side that is older", target, protocol, Protocol.VERSION);
            return;
        }
        active = true;
        nextSummary = System.currentTimeMillis() + Settings.summarySeconds() * 1000L;
        PacketScope.apply();
        String version = ClientSync.class.getPackage().getImplementationVersion();
        Message hello = new Message.Hello(version == null ? "unknown" : version);
        Net.send(GameClient.connection, Protocol.CONNECT_CHANNEL, out -> Codec.encode(hello, out));
        DebugType.Mod.println(PREFIX + "active: the server checks this client's containers and items");
    }

    /**
     * At ZomboidNetData.read HEAD, on the network thread, also while the hooks are shut: true when the packet is the
     * mod's, which the game then queues with no type, in arrival order with the in-scope packets. Its data is filled
     * as read does, without the unknown-id error, only while the hooks are open; the game drops it otherwise.
     */
    public static boolean incoming(ZomboidNetData data, short id, ByteBufferReader in, UdpConnection connection) {
        if (id != Protocol.PACKET_ID) {
            return false;
        }
        data.type = null;
        if (Gate.INSTANCE.active()) {
            data.connection = connection.getConnectedGUID();
            data.buffer.put(in.bb);
            data.buffer.flip();
            own.add(data);
        }
        return true;
    }

    /** At GameClient.mainLoopDealWithNetData HEAD; the game then drops the packet, which has no type. */
    public static void dispatch(ZomboidNetData data) {
        // the game pools its packets: a mark must not outlive this one, and a typed instance carries a stale mark of
        // one the game dropped unrun
        if (!own.remove(data) || data.type != null || !active) {
            return;
        }
        Message m = Codec.decode(data.buffer.bb.duplicate());
        if (m instanceof SyncFrame frame) {
            frame(frame);
        } else if (m instanceof Snapshot snapshot) {
            snapshot(snapshot);
        } else {
            throw new IllegalStateException("the server sent a " + m.getClass().getSimpleName()
                    + ", which only clients send");
        }
    }

    /**
     * At PacketType.onClientPacket HEAD, for every packet: true to drop it ({@code -Dsyncguard.debugDropDelta},
     * {@code -Dsyncguard.debugDropModule}).
     */
    public static boolean dropDelta(PacketType type, ByteBufferReader in) {
        return DeltaDrop.drop(type, in);
    }

    /** Whether this session is checked; the debug drop counts only then. */
    static boolean checking() {
        return active;
    }

    static void dropped() {
        counters.droppedDeltas++;
    }

    // ---- frames (R5)

    private static void frame(SyncFrame frame) {
        counters.frames++;
        counters.entries += frame.entries().size();
        long now = System.currentTimeMillis();
        Asks asks = new Asks();
        for (SyncFrame.Entry e : frame.entries()) {
            if (e.changed() || ledger.isWaiting(e.key())) {
                counters.deferred++;
                Ledger.Waiting pushedOut = ledger.defer(e.key(), e.gone(), e.digest(), frame.version(), now);
                if (pushedOut != null) {
                    check(pushedOut, now, asks);
                }
            } else {
                check(e.key(), e.gone(), e.digest(), frame.version(), now, asks);
            }
        }
        asks.send();
    }

    private static void check(Ledger.Waiting w, long now, Asks asks) {
        check(w.key(), w.gone(), w.digest(), w.version(), now, asks);
    }

    /** One entry against the client's state; a {@code gone} entry: the server no longer finds the object there. */
    private static void check(WireKey wire, boolean gone, long digest, long version, long now, Asks asks) {
        if (ledger.superseded(wire, version) || ledger.givenUp(wire)) {
            ledger.resolved(wire);
            return;
        }
        Lookup.Found found = Lookup.find(wire, GameClient.connection);
        ItemContainer container = found.container();
        if (container == null) {
            if (gone) {
                // neither side has it
                ledger.resolved(wire);
            } else {
                ledger.unresolved(wire, false, digest, version, now, found.name(wire));
            }
            return;
        }
        ledger.resolved(wire);
        counters.checks++;
        if (!wire.isItem()) {
            // a container the server no longer addresses there: its parent's own entry covers the move
            if (!gone) {
                long mine = containerDigest(container);
                if (mine != digest) {
                    mismatch(wire, found.name(wire), container.getItems(), mine, digest, now, asks);
                }
            }
            return;
        }
        InventoryItem item = container.getItemWithID(wire.itemId());
        if (gone) {
            if (item != null) {
                // the server cannot snapshot an item it no longer has there: it compares the whole container
                WireKey whole = WireKey.container(wire.containerBytes());
                long mine = containerDigest(container);
                counters.mismatches++;
                if (ledger.ask(whole, now)) {
                    counters.requested++;
                    if (diff != null) {
                        diff.mismatch(whole, found.name(whole), container.getItems(), mine, 0);
                    }
                    asks.checks.add(new Message.OpenCheck(whole, mine));
                }
            }
            return;
        }
        long mine = item == null ? 0 : ledger.digestOf(item.getID(), ItemDigests.item(item));
        if (item == null || mine != digest) {
            mismatch(wire, found.name(wire), item == null ? List.of() : List.of(item), mine, digest, now, asks);
        }
    }

    // items a snapshot could not make equal count as the server has them, while they stay as it left them
    private static long containerDigest(ItemContainer container) {
        return ItemDigests.container(container, ledger::digestOf);
    }

    private static void mismatch(WireKey wire, String name, Collection<InventoryItem> items, long mine, long server,
            long now, Asks asks) {
        counters.mismatches++;
        if (!ledger.ask(wire, now)) {
            return;
        }
        counters.requested++;
        if (diff != null) {
            diff.mismatch(wire, name, items, mine, server);
        }
        asks.snapshots.add(wire);
    }

    // ---- snapshots (R6)

    private static void snapshot(Snapshot s) {
        WireKey wire = s.key();
        ledger.answered(wire);
        switch (s.state()) {
            case TOO_LARGE -> {
                ledger.giveUp(wire);
                counters.unrepairable++;
                DebugType.Mod.warn(PREFIX + "%s is too large for one snapshot packet; not repaired, and not asked for"
                        + " again this session", Lookup.find(wire, GameClient.connection).name(wire));
            }
            case GONE -> {
                if (debug()) {
                    DebugType.Mod.debugln(PREFIX + "snapshot of %s: the server no longer has it there",
                            Lookup.find(wire, GameClient.connection).name(wire));
                }
            }
            case PRESENT -> applied(s, Reconcile.apply(s, GameClient.connection, diff));
        }
    }

    private static void applied(Snapshot s, Reconcile.Result r) {
        WireKey wire = s.key();
        switch (r.outcome()) {
            case NOT_FOUND -> {
                if (debug()) {
                    DebugType.Mod.debugln(PREFIX + "snapshot of %s: no longer on this client; not applied", r.name());
                }
                return;
            }
            case MATCHED -> {
                counters.snapshotsApplied++;
                ledger.applied(wire, s.version());
                stuck(r);
                if (debug()) {
                    DebugType.Mod.debugln(PREFIX + "snapshot of %s: nothing to change, the client had caught up",
                            r.name());
                }
                return;
            }
            case REPAIRED -> {
                counters.snapshotsApplied++;
                counters.repaired++;
                ledger.applied(wire, s.version());
                DebugType.Mod.println(PREFIX + "repaired %s from the server's state:%s", r.name(), ids(r));
                stuck(r);
            }
            case UNREPAIRABLE -> {
                counters.unrepairable++;
                ledger.giveUp(wire);
                String line = PREFIX + "could not repair " + r.name() + ": " + r.failure() + "; not asked for again"
                        + " this session." + ids(r);
                if (r.cause() != null) {
                    DebugType.Mod.printException(r.cause(), line, LogSeverity.Error);
                } else {
                    DebugType.Mod.warn(line);
                }
            }
        }
        if (r.changedAnything()) {
            notices.repaired();
            notice(System.currentTimeMillis());
        }
    }

    /**
     * Items the snapshot could not make equal: compared as the server has them while they stay as it left them, so
     * the rest of their container is still checked. Logged once per item.
     */
    private static void stuck(Reconcile.Result r) {
        List<Integer> first = new ArrayList<>();
        for (Reconcile.Stuck item : r.stuck()) {
            if (ledger.accept(item.id(), item.mine(), item.server())) {
                first.add(item.id());
            }
        }
        if (!first.isEmpty()) {
            counters.stuckItems += first.size();
            DebugType.Mod.warn(PREFIX + "items %s in %s still differ from the server's after its snapshot, in fields"
                    + " a snapshot cannot set; until either side changes them they are compared as the server has"
                    + " them", list(first), r.name());
        }
    }

    private static boolean debug() {
        return DebugType.Mod.isEnabled(LogSeverity.Debug);
    }

    private static String ids(Reconcile.Result r) {
        return (r.added().isEmpty() ? "" : " added " + list(r.added()))
                + (r.removed().isEmpty() ? "" : " removed " + list(r.removed()))
                + (r.changed().isEmpty() ? "" : " changed " + list(r.changed()));
    }

    private static String list(List<Integer> ids) {
        return ids.size() <= MAX_IDS ? ids.toString()
                : ids.subList(0, MAX_IDS) + " and " + (ids.size() - MAX_IDS) + " more";
    }

    // ---- open checks (R7)

    /** At ItemContainer.requestServerItemsForContainer HEAD: the game fills the container; checked once it did. */
    public static void itemsRequested(ItemContainer container) {
        WireKey wire = openKey(container);
        if (wire != null) {
            ledger.filling(wire, System.currentTimeMillis());
        }
    }

    /** From the loot window's Lua: the container it shows now. One the game is still filling waits for that. */
    public static void containerShown(ItemContainer container) {
        if (!container.isExplored()) {
            return;
        }
        WireKey wire = openKey(container);
        if (wire != null && ledger.open(wire, System.currentTimeMillis())) {
            openCheck(wire, container);
        }
    }

    // null when not checked: inactive, or a container the game does not address (floor, out of the world)
    private static WireKey openKey(ItemContainer container) {
        if (!active) {
            return null;
        }
        ContainerID id = Containers.locate(container);
        ContainerKey key = id == null ? null : Containers.keyOf(id);
        return key == null ? null : WireKey.container(Containers.wire(id));
    }

    private static void openCheck(WireKey wire, ItemContainer container) {
        counters.openChecks++;
        send(new Message.OpenCheck(wire, containerDigest(container)));
    }

    // ---- every frame

    /**
     * At GameClient.update RETURN, also while the hooks are shut: says a pending Bye. Then, while checking, retries
     * entries not found yet, runs open checks that waited, shows a due notice and writes the summary.
     */
    public static void tick() {
        mainThread = Thread.currentThread();
        if (byePending) {
            byePending = false;
            bye();
        }
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Ledger.Unresolved> retry = new ArrayList<>();
        List<Ledger.Unresolved> expired = new ArrayList<>();
        if (ledger.retry(now, retry, expired)) {
            Asks asks = new Asks();
            retry.forEach(u -> check(u.key(), u.gone(), u.digest(), u.version(), now, asks));
            ledger.due(now).forEach(w -> check(w, now, asks));
            asks.send();
            expiredLine(expired);
            for (WireKey wire : ledger.filled(now)) {
                ItemContainer container = Lookup.find(wire, GameClient.connection).container();
                if (container != null) {
                    openCheck(wire, container);
                }
            }
        }
        notice(now);
        if (now >= nextSummary) {
            nextSummary = now + Settings.summarySeconds() * 1000L;
            summary();
        }
    }

    private static void expiredLine(List<Ledger.Unresolved> expired) {
        if (expired.isEmpty()) {
            return;
        }
        counters.pendingUnresolved += expired.size();
        List<String> names = expired.stream().limit(MAX_IDS).map(Ledger.Unresolved::name).toList();
        DebugType.Mod.println(PREFIX + "%d objects the server checked were not found on this client within %d s and"
                + " are not checked: %s%s", expired.size(), Ledger.UNRESOLVED_MS / 1000, String.join("; ", names),
                expired.size() > MAX_IDS ? "; and " + (expired.size() - MAX_IDS) + " more" : "");
    }

    // ---- notice and summary (R9)

    private static void notice(long now) {
        IsoPlayer player = IsoPlayer.getInstance();
        if (player == null) {
            return;
        }
        int repairs = notices.due(now);
        if (repairs > 0) {
            counters.notices++;
            HaloTextHelper.addText(player, Translator.getText(NOTICE, repairs));
            DebugType.Mod.println(PREFIX + "repair notice shown for %d repairs", repairs);
        }
    }

    /** One line of {@code key=value} numbers, as the server's, for the load-test tools. */
    private static void summary() {
        StringBuilder line = new StringBuilder("scope=client");
        counters.appendTo(line, ledger.unresolvedCount(), ledger.waitingCount());
        DebugType.Mod.println(PREFIX + "%s", line);
    }

    // ---- session end

    /** At IngameState.exit HEAD: ends the server's session. */
    public static void leave() {
        if (active) {
            UdpConnection c = GameClient.connection;
            if (c != null && GameClient.instance.connected && Net.canSend(c)) {
                bye();
            }
            summary();
            DebugType.Mod.println(PREFIX + "left the server");
        }
        reset();
    }

    // before each join and after leaving
    private static void reset() {
        active = false;
        byePending = false;
        // marks of packets the game dropped without running them (a lost connection)
        own.clear();
        PacketScope.restore();
        ledger = new Ledger();
        counters = new ClientCounters();
        notices = new Notices();
        diff = Settings.fieldDiff() ? new FieldDiff() : null;
    }

    /** Undoes what the client changed in the game, after a failure; the hooks are shut already. */
    public static void stop() {
        boolean was = active;
        active = false;
        own.clear();
        PacketScope.restore();
        if (!was) {
            return;
        }
        summary();
        UdpConnection c = GameClient.connection;
        if (Thread.currentThread() == mainThread && c != null && Net.canSend(c)) {
            bye();
        } else {
            byePending = true;
        }
    }

    // the server stops sending to this client
    private static void bye() {
        UdpConnection c = GameClient.connection;
        if (c != null) {
            Net.send(c, Protocol.SHARED_CHANNEL, out -> Codec.encode(new Message.Bye(), out));
        }
    }

    private static void send(Message m) {
        Net.send(GameClient.connection, Protocol.SHARED_CHANNEL, out -> Codec.encode(m, out));
    }
}
