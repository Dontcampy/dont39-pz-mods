package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.Overlay;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Thrown;
import zombie.core.Core;
import zombie.scripting.ScriptManager;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** Frames, for the overlay; script files; Lua resets, which a game start or a mod list change runs. */
@Hooks(Core.class)
public final class CoreHooks {
    private CoreHooks() {
    }

    @Inject(method = "StartFrameUI", at = @At("RETURN"))
    public static void uiPassStarted(CallbackInfoReturnable<Boolean> cir) {
        Overlay.uiPassStarted(cir.getReturnValueZ());
    }

    @Inject(method = "EndFrameUI", at = @At("HEAD"))
    public static void frameEnds(CallbackInfo ci) {
        Overlay.frameEnds(PROGRESS);
    }

    // ScriptManager.LoadFile calls it once per script file; LoadFile's own arguments include a game type
    @Inject(method = "DoFrameReady", at = @At("HEAD"))
    public static void frameReady(CallbackInfo ci) {
        if (PROGRESS.countsScripts()) {
            PROGRESS.script(ScriptManager.getCurrentLoadFileAbsPath(), ScriptManager.getCurrentLoadFileName(),
                    ScriptManager.instance.tempFileToModMap.size());
        }
        Loading.tick();
    }

    @Inject(method = "ResetLua(Ljava/lang/String;Ljava/lang/String;)V", at = @At("HEAD"))
    public static void resetBegins(String activeMods, String reason, CallbackInfo ci) {
        PROGRESS.resetBegins("default".equalsIgnoreCase(activeMods), System.nanoTime());
    }

    @Inject(method = "ResetLua(Ljava/lang/String;Ljava/lang/String;)V", at = @At("FINALLY"))
    public static void resetEnds(CallbackInfo ci, @Thrown Throwable thrown) {
        PROGRESS.resetEnds(thrown != null, System.nanoTime());
    }
}
