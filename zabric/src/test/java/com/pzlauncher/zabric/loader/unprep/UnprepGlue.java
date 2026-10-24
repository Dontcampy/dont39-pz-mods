package com.pzlauncher.zabric.loader.unprep;

import me.zed_0xff.zombie_buddy.Patch;

/** Woven without preparing its target, for {@code PatchTargetsTest}. */
public final class UnprepGlue {
    private UnprepGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.loader.unprep.target.Host", methodName = "run")
    public static final class Host_run {
        @Patch.OnEnter
        public static void enter() {
        }
    }
}
