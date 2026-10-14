package com.pzlauncher.zabric.hook.check.late;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.check.CheckGate;
import me.zed_0xff.zombie_buddy.Patch;

// Glue on classes WeaveCheckTest defines after the pass: one woven, one whose bound argument type matches no method.
public final class LateGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-late");

    private LateGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.LateGood", methodName = "twice", strictMatch = true)
    public static final class LateGood_twice {
        private LateGood_twice() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int arg0) {
            MOD.active(CheckGate.INSTANCE);
        }
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.LateMissing", methodName = "count", strictMatch = true)
    public static final class LateMissing_count {
        private LateMissing_count() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) String arg0) {
            MOD.active(CheckGate.INSTANCE);
        }
    }
}
