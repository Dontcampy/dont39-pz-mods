package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.characters.IsoPlayer;
import zombie.network.GameClient;
import zombie.network.ZomboidNetData;

/** The client's queued packets, its frame and its PlayerConnect. */
@Hooks(value = GameClient.class, gate = Gate.class)
public final class ClientNetHooks {
    private ClientNetHooks() {
    }

    @Inject(method = "mainLoopDealWithNetData", at = @At("HEAD"))
    public static void dispatch(ZomboidNetData data, CallbackInfo ci) {
        ClientSync.dispatch(data);
    }

    // every frame; also while shut, to say a Bye a failure left pending
    @Inject(method = "update", at = @At("RETURN"), always = true)
    public static void frame(CallbackInfo ci) {
        ClientSync.tick();
    }

    @Inject(method = "sendPlayerConnect", at = @At("RETURN"))
    public static void playerConnectSent(IsoPlayer player, CallbackInfo ci) {
        ClientSync.playerConnectSent();
    }
}
