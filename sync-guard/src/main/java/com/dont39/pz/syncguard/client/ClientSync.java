package com.dont39.pz.syncguard.client;

import com.dont39.pz.syncguard.game.Net;
import com.dont39.pz.syncguard.game.OwnNetData;
import com.dont39.pz.syncguard.game.PacketScope;
import com.dont39.pz.syncguard.protocol.Codec;
import com.dont39.pz.syncguard.protocol.Message;
import com.dont39.pz.syncguard.protocol.Protocol;
import com.dont39.pz.syncguard.protocol.Trailer;
import zombie.core.network.ByteBufferReader;
import zombie.debug.DebugType;
import zombie.gameStates.ConnectToServerState;
import zombie.network.GameClient;
import zombie.network.ZomboidNetData;

import java.nio.ByteBuffer;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * The client's side: turns on for a join whose server advertised the protocol (R8) and receives the server's
 * messages. Checking frames and applying snapshots come in the next step. Main thread, except {@link #incoming}.
 */
public final class ClientSync {
    // this join's: whether the server offers our protocol, and whether the client said Hello
    private static boolean offered;
    private static boolean active;
    private static boolean frameStubLogged;
    private static boolean snapshotStubLogged;

    private ClientSync() {
    }

    /**
     * At ConnectToServerState.exit HEAD. Read here, not where the game parses the connection-details: a client that
     * enables the mod only with the server's mod list loads it inside the join, after that.
     */
    public static void joinEnds(ConnectToServerState join) {
        leave();
        String target = GameClient.ip + ":" + GameClient.port;
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
        offered = true;
        DebugType.Mod.println(PREFIX + "join to %s: the server runs Sync Guard; checks start once the player is in",
                target);
    }

    /** At GameClient.sendPlayerConnect RETURN: PlayerConnect is out, so a Hello on its channel follows it. */
    public static void playerConnectSent() {
        if (!offered || active) {
            return;
        }
        active = true;
        PacketScope.apply();
        String version = ClientSync.class.getPackage().getImplementationVersion();
        Message hello = new Message.Hello(version == null ? "unknown" : version);
        Net.send(GameClient.connection, Protocol.CONNECT_CHANNEL, out -> Codec.encode(hello, out));
        DebugType.Mod.println(PREFIX + "active: the server checks this client's containers and items");
    }

    /** At GameClient.addIncoming HEAD, on the network thread: true when the packet is the mod's and now queued. */
    public static boolean incoming(short id, ByteBufferReader in) {
        if (id != Protocol.PACKET_ID) {
            return false;
        }
        if (GameClient.connection != null) {
            // the game's main-loop queue, in order with the in-scope packets
            GameClient.MainLoopNetDataQ.add(new OwnNetData(Net.payload(in),
                    GameClient.connection.getConnectedGUID()));
        }
        return true;
    }

    /** At GameClient.mainLoopDealWithNetData HEAD; the game then discards the packet, which has no type. */
    public static void dispatch(ZomboidNetData data) {
        if (!(data instanceof OwnNetData own) || !active) {
            return;
        }
        Message m = Codec.decode(ByteBuffer.wrap(own.payload()));
        if (m instanceof Message.SyncFrame frame) {
            frame(frame);
        } else if (m instanceof Message.Snapshot snapshot) {
            snapshot(snapshot);
        } else {
            throw new IllegalStateException("the server sent a " + m.getClass().getSimpleName()
                    + ", which only clients send");
        }
    }

    // STUB (next step, R5): compare each entry with the client's state, ask for snapshots of those that differ
    private static void frame(Message.SyncFrame frame) {
        if (!frameStubLogged) {
            frameStubLogged = true;
            DebugType.Mod.println(PREFIX + "receives sync frames (%d entries in the first); this version does not"
                    + " check them yet", frame.entries().size());
        }
    }

    // STUB (next step, R6): reconcile the container or item in place
    private static void snapshot(Message.Snapshot snapshot) {
        if (!snapshotStubLogged) {
            snapshotStubLogged = true;
            DebugType.Mod.println(PREFIX + "receives snapshots; this version does not apply them yet");
        }
    }

    /** At IngameState.exit HEAD, and before each join: ends the last server's session. */
    public static void leave() {
        if (active) {
            DebugType.Mod.println(PREFIX + "left the server");
        }
        offered = false;
        active = false;
        PacketScope.restore();
    }

    /** Undoes what the client changed in the game, after a failure; the hooks are shut already. */
    public static void stop() {
        active = false;
        PacketScope.restore();
    }
}
