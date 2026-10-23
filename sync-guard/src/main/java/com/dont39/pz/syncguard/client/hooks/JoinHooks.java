package com.dont39.pz.syncguard.client.hooks;

import com.dont39.pz.syncguard.client.ClientSync;
import com.dont39.pz.syncguard.game.Gate;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;
import zombie.gameStates.ConnectToServerState;

/** A join's end, where the client learns whether the server runs the mod. */
@Hooks(value = ConnectToServerState.class, gate = Gate.class)
public final class JoinHooks {
    private JoinHooks() {
    }

    @Inject(method = "exit", at = @At("HEAD"))
    public static void joinEnds(@Self ConnectToServerState join, CallbackInfo ci) {
        ClientSync.joinEnds(join);
    }
}
