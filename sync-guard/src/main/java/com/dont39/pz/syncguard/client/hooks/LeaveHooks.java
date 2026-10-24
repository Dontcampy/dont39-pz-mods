package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.gameStates.IngameState;
import zombie.network.GameServer;

/** Leaving a game, where a server's session ends. */
@Hooks(value = IngameState.class, gate = Gate.class)
public final class LeaveHooks {
    private LeaveHooks() {
    }

    @Inject(method = "exit", at = @At("HEAD"))
    public static void leave(CallbackInfo ci) {
        if (!GameServer.server) {
            ClientSync.leave();
        }
    }
}
