package com.example.zabricsample;

import me.zed_0xff.zombie_buddy.Patch;

/** Fails to compile: a hand-written loader patch without @UnsafeLoaderPatch. */
@Patch(className = "zombie.GameTime", methodName = "getHour")
public final class LegacyPatch {
    private LegacyPatch() {
    }

    @Patch.OnExit
    public static void exit() {
    }
}
