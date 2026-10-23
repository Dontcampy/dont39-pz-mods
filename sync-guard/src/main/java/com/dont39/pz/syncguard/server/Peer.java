package com.dont39.pz.syncguard.server;

import com.dont39.pz.syncguard.protocol.Key;
import com.dont39.pz.syncguard.protocol.WireKey;
import zombie.characters.IsoPlayer;
import zombie.core.raknet.UdpConnection;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** One connection's sync state on the server. Main thread only. */
final class Peer {
    /** A snapshot the client asked for. */
    record Job(Key key, WireKey wire) {
    }

    final UdpConnection connection;
    boolean active;
    /** Why the server stopped answering this connection, or null. */
    String stopped;
    String modVersion;

    /** Every object this connection was sent or changed, with the frame that last covered it (R1). */
    final Map<Key, Long> versions = new HashMap<>();
    /** This tick's objects, kept until the connection is fully connected. */
    final Map<Key, ServerSync.Touch> touched = new LinkedHashMap<>();
    /** The last frame's number; snapshots carry it as their version. */
    long frame;
    final ArrayDeque<Job> snapshots = new ArrayDeque<>();
    final Set<Key> queued = new HashSet<>();

    final Counters session;

    Peer(UdpConnection connection, Counters total) {
        this.connection = connection;
        this.session = new Counters(total);
    }

    String name() {
        String user = connection.getUserName();
        return user != null ? user : "connection " + connection.getIndex();
    }

    /** The connection's first player, or null. */
    IsoPlayer player() {
        for (IsoPlayer p : connection.players) {
            if (p != null) {
                return p;
            }
        }
        return null;
    }
}
