package com.example.zabricsample.hooks;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import zombie.GameTime;

/** Fails to compile: Mixin's INVOKE injection point is not supported. */
@Hooks(GameTime.class)
public final class InvokeHooks {
    private InvokeHooks() {
    }

    @Inject(method = "update", at = @At("INVOKE"))
    public static void beforeCall(CallbackInfo ci) {
    }
}
