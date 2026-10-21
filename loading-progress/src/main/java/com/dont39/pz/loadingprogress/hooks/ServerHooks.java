package com.dont39.pz.loadingprogress.hooks;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.network.GameServer;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** A dedicated server, where the mod only says that it idles. */
@Hooks(GameServer.class)
public final class ServerHooks {
    private ServerHooks() {
    }

    // the game log is up; a server that preloads the mod without listing it never runs MainEntry
    @Inject(method = "doMinimumInit", at = @At("HEAD"))
    public static void init(CallbackInfo ci) {
        PROGRESS.client();
    }
}
