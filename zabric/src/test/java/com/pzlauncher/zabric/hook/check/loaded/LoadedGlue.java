package com.pzlauncher.zabric.hook.check.loaded;

import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import me.zed_0xff.zombie_buddy.Patch;

// Fails verification once inlined: a conditional among a constructor's arguments, which WeaveGlue avoids.
public final class LoadedGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-loaded");

    private LoadedGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.Loaded", methodName = "value", strictMatch = true)
    public static final class Loaded_value {
        private Loaded_value() {
        }

        @Patch.OnEnter
        public static void enter(
                @Patch.Argument(0) int arg0,
                @Patch.Local("h0$active") boolean h0$active) {
            h0$active = MOD.active(MOD.defaultGate());
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(
                @Patch.Argument(0) int arg0,
                @Patch.Return int ret,
                @Patch.Thrown Throwable thrown,
                @Patch.Local("h0$active") boolean h0$active) {
            if (h0$active) {
                try {
                    CallbackInfoReturnable<Integer> ci = new CallbackInfoReturnable<>("value", false,
                            thrown == null ? ret : null);
                    ci.getReturnValue();
                } catch (Throwable t) {
                    MOD.failed(MOD.defaultGate(), "running LoadedHooks.after on Loaded.value", t);
                }
            }
        }
    }
}
