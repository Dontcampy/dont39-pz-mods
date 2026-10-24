package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.core.network.ByteBufferReader;
import zombie.core.raknet.UdpConnection;
import zombie.network.ZomboidNetData;

/**
 * The client's received packets as GameClient.addIncoming reads them for its queue. On the server the mod's id never
 * gets here: GameServer.addIncoming is cancelled for it first.
 */
@Hooks(value = ZomboidNetData.class, gate = Gate.class)
public final class NetDataHooks {
    private NetDataHooks() {
    }

    // Network thread. Also after a failure, until the server has the Bye: read would log the mod's id as unknown.
    @Inject(method = "read", at = @At("HEAD"), cancellable = true, always = true)
    public static void read(@Self ZomboidNetData data, short id, ByteBufferReader in, UdpConnection connection,
            CallbackInfo ci) {
        if (ClientSync.incoming(data, id, in, connection)) {
            ci.cancel();
        }
    }
}
