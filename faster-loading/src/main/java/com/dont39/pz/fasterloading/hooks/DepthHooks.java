package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.Mod;
import com.dont39.pz.fasterloading.settings.Fix;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import zombie.tileDepth.TileDepthTextureManager;

/** Where the game queues the depth maps of the game and each mod, at launch, on a join and back at the menu. */
@Hooks(TileDepthTextureManager.class)
public final class DepthHooks {
    private static final ModHooks HOOKS = ZabricHooks.mod(Mod.MOD_ID);

    // the game thread's only; -1 when HEAD did not run the fix
    private static int before = -1;

    private DepthHooks() {
    }

    // Each mod's depth maps decode one at a time under that mod's lock, and the game queues them mod after mod, so the
    // file workers mostly wait on one lock. Nothing pumps the queue inside init, so all its tasks are still unsorted at
    // RETURN, where new priorities change their order and nothing else.
    @Inject(method = "init()V", at = @At("HEAD"))
    public static void initBegins(CallbackInfo ci) {
        before = Session.on(Fix.DEPTH_MAP_ORDER) && DepthOrder.widened() ? DepthOrder.queued() : -1;
    }

    @Inject(method = "init()V", at = @At("RETURN"))
    public static void initEnds(CallbackInfo ci) {
        int queued = before;
        before = -1;
        // also runs after the mod's hooks stopped during the call
        if (queued >= 0 && HOOKS.open()) {
            DepthOrder.reorder(queued);
        }
    }
}
