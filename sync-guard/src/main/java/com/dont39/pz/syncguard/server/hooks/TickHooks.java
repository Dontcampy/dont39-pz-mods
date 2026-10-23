package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.network.ServerMap;

/** The end of the server's world tick. */
@Hooks(value = ServerMap.class, gate = Gate.class)
public final class TickHooks {
    private TickHooks() {
    }

    // called only by GameServer.main, once per world tick after every send of it, before cells unload
    @Inject(method = "postupdate", at = @At("HEAD"))
    public static void endOfTick(CallbackInfo ci) {
        ServerSync.endOfTick();
    }
}
