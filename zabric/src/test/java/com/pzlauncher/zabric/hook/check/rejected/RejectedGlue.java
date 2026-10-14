package com.pzlauncher.zabric.hook.check.rejected;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import me.zed_0xff.zombie_buddy.Patch;

// Glue ByteBuddy refuses: the bound return type is not the target's.
public final class RejectedGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-rejected");

    private RejectedGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.Rejected", methodName = "name", strictMatch = true)
    public static final class Rejected_name {
        private Rejected_name() {
        }

        @Patch.OnExit
        public static void exit(@Patch.Return int ret) {
            MOD.open();
        }
    }
}
