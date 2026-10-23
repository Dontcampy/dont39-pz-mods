package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.GameServer;
import zombie.network.ZomboidNetData;

/** The server's packet queues and connections. */
@Hooks(value = GameServer.class, gate = Gate.class)
public final class NetHooks {
    private NetHooks() {
    }

    // Network thread. Cancels only the mod's own id: the game would log it as unknown and run its packet anticheat,
    // which can kick or ban. Also after a failure, as clients still send until they learn of it.
    @Inject(method = "addIncoming", at = @At("HEAD"), cancellable = true, always = true)
    public static void incoming(short id, ByteBufferReader in, UdpConnection connection, CallbackInfo ci) {
        if (ServerSync.incoming(id, in, connection)) {
            ci.cancel();
        }
    }

    @Inject(method = "mainLoopDealWithNetData", at = @At("HEAD"))
    public static void dispatch(ZomboidNetData data, CallbackInfo ci) {
        ServerSync.dispatch(data);
    }

    @Inject(method = "disconnect", at = @At("HEAD"))
    public static void disconnect(UdpConnection connection, String reason, CallbackInfo ci) {
        ServerSync.disconnected(connection);
    }
}
