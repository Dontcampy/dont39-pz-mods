package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.network.GameClient;
import zombie.network.ZomboidNetData;

/** The client's packet queue and its PlayerConnect. */
@Hooks(value = GameClient.class, gate = Gate.class)
public final class ClientNetHooks {
    private ClientNetHooks() {
    }

    // Network thread. Cancels only the mod's own id, which the game would log as unknown.
    @Inject(method = "addIncoming", at = @At("HEAD"), cancellable = true)
    public static void incoming(short id, ByteBufferReader in, CallbackInfo ci) {
        if (ClientSync.incoming(id, in)) {
            ci.cancel();
        }
    }

    @Inject(method = "mainLoopDealWithNetData", at = @At("HEAD"))
    public static void dispatch(ZomboidNetData data, CallbackInfo ci) {
        ClientSync.dispatch(data);
    }

    @Inject(method = "sendPlayerConnect", at = @At("RETURN"))
    public static void playerConnectSent(IsoPlayer player, CallbackInfo ci) {
        ClientSync.playerConnectSent();
    }
}
