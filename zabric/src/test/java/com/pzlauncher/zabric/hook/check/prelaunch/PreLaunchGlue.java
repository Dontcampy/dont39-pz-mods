package com.pzlauncher.zabric.hook.check.prelaunch;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.check.PreLaunchHits;
import me.zed_0xff.zombie_buddy.Patch;

// The target is defined after preLaunch opened the mod, before any pass.
public final class PreLaunchGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-prelaunch");

    private PreLaunchGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.PreLaunchGood", methodName = "twice", strictMatch = true)
    public static final class PreLaunchGood_twice {
        private PreLaunchGood_twice() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int arg0) {
            if (MOD.active(MOD.defaultGate())) {
                PreLaunchHits.HITS.incrementAndGet();
            }
        }
    }
}
