package com.dont39.pz.syncguard.server.hooks;

import com.dont39.pz.syncguard.game.Gate;
import com.dont39.pz.syncguard.server.ServerSync;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.popman.PoolCaps;

/**
 * The start of each pass of the server's main loop: GameServer.main calls this first, also in the passes that only
 * take packets between ticks, and nothing else calls it.
 */
@Hooks(value = PoolCaps.class, gate = Gate.class)
public final class LoopHooks {
    private LoopHooks() {
    }

    @Inject(method = "updateServerCaps", at = @At("HEAD"))
    public static void loopStarts(CallbackInfo ci) {
        ServerSync.loopStarts();
    }
}
