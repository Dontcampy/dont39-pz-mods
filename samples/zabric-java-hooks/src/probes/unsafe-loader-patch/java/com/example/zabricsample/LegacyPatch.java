package com.example.zabricsample;

import com.pzlauncher.zabric.hook.UnsafeLoaderPatch;
import me.zed_0xff.zombie_buddy.Patch;

/** Compiles, with the processor's warning: the marker is an opt-out, not a recommendation. */
@Patch(className = "zombie.GameTime", methodName = "getHour")
@UnsafeLoaderPatch
public final class LegacyPatch {
    private LegacyPatch() {
    }

    @Patch.OnExit
    public static void exit() {
    }
}
