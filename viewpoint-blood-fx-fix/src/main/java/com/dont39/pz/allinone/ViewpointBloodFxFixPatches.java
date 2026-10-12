package com.dont39.pz.allinone;

import com.dont39.pz.viewpointbloodfxfix.AimShift;
import com.dont39.pz.viewpointbloodfxfix.LiftOffsets;
import me.zed_0xff.zombie_buddy.Patch;

// ZombieBuddy only picks up @Patch classes from exactly javaPkgName, so they live here, not in the feature package.
public final class ViewpointBloodFxFixPatches {
    private ViewpointBloodFxFixPatches() {
    }

    @Patch(className = "bloodfx.Gpu", methodName = "render")
    public static class BloodRender {
        @Patch.OnEnter
        public static void enter() {
            LiftOffsets.liftBlood();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            LiftOffsets.restoreBlood();
        }
    }

    @Patch(className = "bloodfx.GoreGpu", methodName = "render")
    public static class GoreRender {
        @Patch.OnEnter
        public static void enter() {
            LiftOffsets.liftGore();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            LiftOffsets.restoreGore();
        }
    }

    @Patch(className = "bloodfx.Aim", methodName = "target")
    public static class AimTarget {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object character) {
            AimShift.lower(character);
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            AimShift.restore();
        }
    }
}
