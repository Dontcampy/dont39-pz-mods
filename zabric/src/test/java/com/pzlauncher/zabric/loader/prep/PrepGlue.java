package com.pzlauncher.zabric.loader.prep;

import me.zed_0xff.zombie_buddy.Patch;

/** Patches in the shape of Zabric's glue, for {@code PatchTargetsTest}. */
public final class PrepGlue {
    private PrepGlue() {
    }

    @Patch(className = "com.pzlauncher.zabric.loader.prep.target.Host", methodName = "run")
    public static final class Host_run {
        @Patch.OnEnter
        public static void enter() {
        }
    }

    @Patch(className = "com.pzlauncher.zabric.loader.prep.target.Second", methodName = "run")
    public static final class Second_run {
        @Patch.OnEnter
        public static void enter() {
        }
    }

    // not loaded: preparing loads its member types, not it
    @Patch(className = "com.pzlauncher.zabric.loader.prep.target.Unloaded", methodName = "run")
    public static final class Unloaded_run {
        @Patch.OnEnter
        public static void enter() {
        }
    }
}
