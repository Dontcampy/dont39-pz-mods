package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;

/** Author code: a handler that runs while its gate is inactive. */
@Hooks(value = Target.class, gate = TestGate.class)
public final class AlwaysHooks {
    private AlwaysHooks() {
    }

    @Inject(method = "ping", at = @At("FINALLY"), always = true)
    public static void afterPing(CallbackInfo ci) {
        Events.add("always afterPing");
    }
}
