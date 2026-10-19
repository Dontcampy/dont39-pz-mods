package com.pzlauncher.zabric.hook.check.signature;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import me.zed_0xff.zombie_buddy.Patch;

// The target names a type in a signature that nothing has loaded before the weave check retransforms the target.
public final class SignatureGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-signature");

    private SignatureGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.SignatureTarget", methodName = "twice", strictMatch = true)
    public static final class SignatureTarget_twice {
        private SignatureTarget_twice() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int arg0) {
            MOD.active(MOD.defaultGate());
        }
    }
}
