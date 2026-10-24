package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.LocalRef;
import com.pzlauncher.zabric.hook.Self;
import com.pzlauncher.zabric.hook.Share;
import com.pzlauncher.zabric.hook.Thrown;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.IConnection;
import zombie.network.PacketTypes.PacketType;

/** Where every packet is sent and every client packet is handled on the server: touch recording (R3). */
@Hooks(value = PacketType.class, gate = Gate.class)
public final class PacketTypeHooks {
    private PacketTypeHooks() {
    }

    // every packet on both sides: an early return unless an in-scope packet was just written
    @Inject(method = "send", at = @At("HEAD"))
    public static void send(@Self PacketType type, IConnection connection, CallbackInfo ci) {
        ServerSync.sending(type, connection);
    }

    @Inject(method = "onServerPacket", at = @At("HEAD"))
    public static void receiving(@Self PacketType type, ByteBufferReader in, UdpConnection connection,
            CallbackInfo ci, @Share("start") LocalRef<Integer> start) {
        start.set(ServerSync.receiving(type, in));
    }

    // FINALLY: rejected and failed packets count as touched too
    @Inject(method = "onServerPacket", at = @At("FINALLY"))
    public static void received(@Self PacketType type, ByteBufferReader in, UdpConnection connection,
            CallbackInfo ci, @Thrown Throwable thrown, @Share("start") LocalRef<Integer> start) {
        Integer at = start.get();
        if (at != null) {
            ServerSync.received(type, in, connection, at);
        }
    }
}
