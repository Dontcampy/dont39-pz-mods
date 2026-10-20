package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.Self;

/** Author code: a cancellable HEAD on a private void method. */
@Hooks(Nested.class)
public final class NestedHooks {
    private NestedHooks() {
    }

    // the shape of a step held across frames until work elsewhere is done
    @Inject(method = "step", at = @At("HEAD"), cancellable = true)
    public static void hold(@Self Nested self, CallbackInfo ci) {
        if (self.item.state != Nested.State.READY) {
            Events.add("held");
            ci.cancel();
        }
    }
}
