package com.pzlauncher.zabric.hook.check.nested;

import com.pzlauncher.zabric.hook.ModHooks;
import com.pzlauncher.zabric.hook.ZabricHooks;
import com.pzlauncher.zabric.hook.check.NestedHits;
import me.zed_0xff.zombie_buddy.Patch;

// The target loads inside another transformation, where no transformer of the agent sees it; see NestedAnchor.
public final class NestedGlue {
    public static final ModHooks MOD = ZabricHooks.mod("check-nested");

    private NestedGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.NestedTarget", methodName = "twice", strictMatch = true)
    public static final class NestedTarget_twice {
        private NestedTarget_twice() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int arg0) {
            if (MOD.active(MOD.defaultGate())) {
                NestedHits.HITS.incrementAndGet();
            }
        }
    }

    @Patch(className = "com.pzlauncher.zabric.hook.check.NestedAnchor", methodName = "value", strictMatch = true)
    public static final class NestedAnchor_value {
        private NestedAnchor_value() {
        }

        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) int arg0) {
            MOD.active(MOD.defaultGate());
        }
    }
}
