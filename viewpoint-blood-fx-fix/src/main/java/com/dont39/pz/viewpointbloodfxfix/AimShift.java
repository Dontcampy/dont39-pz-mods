package com.dont39.pz.viewpointbloodfxfix;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import zombie.iso.IsoMovingObject;

/**
 * Aim.target tests the crosshair ray against flat-ground bones, but Viewpoint draws the zombie raised by the relief where
 * it stands; lowering the ray by that much for the call gives the same test, with the hit point on flat ground.
 */
public final class AimShift {
    private static final Field LAST;
    private static final Field OX, OY, OH, DX, DY, DH, NANOS;
    private static final Constructor<?> RAY;
    private static final String PROBLEM;

    static {
        Field last = null, ox = null, oy = null, oh = null, dx = null, dy = null, dh = null, nanos = null;
        Constructor<?> ray = null;
        String problem = null;
        try {
            Class<?> aim = Class.forName("bloodfx.Aim");
            Class<?> r = Class.forName("bloodfx.Aim$Ray");
            last = LiftOffsets.field(aim, "last");
            ox = LiftOffsets.field(r, "ox");
            oy = LiftOffsets.field(r, "oy");
            oh = LiftOffsets.field(r, "oh");
            dx = LiftOffsets.field(r, "dx");
            dy = LiftOffsets.field(r, "dy");
            dh = LiftOffsets.field(r, "dh");
            nanos = LiftOffsets.field(r, "nanos");
            ray = r.getDeclaredConstructor(double.class, double.class, double.class, double.class, double.class,
                    double.class, long.class);
            ray.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            problem = e.toString();
        }
        LAST = last;
        OX = ox;
        OY = oy;
        OH = oh;
        DX = dx;
        DY = dy;
        DH = dh;
        NANOS = nanos;
        RAY = ray;
        PROBLEM = problem;
    }

    private static Object original, lowered;
    private static boolean reported;

    private AimShift() {
    }

    public static void lower(Object character) {
        if (!ViewpointBloodFxFix.active()) {
            return;
        }
        if (PROBLEM != null) {
            if (!reported) {
                reported = true;
                ViewpointBloodFxFix.warn("aimed cuts not corrected for terrain relief: " + PROBLEM);
            }
            return;
        }
        try {
            Object ray = LAST.get(null);
            Object lift = Relief.current();
            if (ray == null || lift == null || !(character instanceof IsoMovingObject zombie)) {
                return;
            }
            float dh = Relief.at(lift, zombie.getX(), zombie.getY());
            if (ViewpointBloodFxFix.debug()) {
                ViewpointBloodFxFix.log(String.format("aim: zombie at %.2f %.2f z=%.2f, relief %.3f, ray h %.3f -> %.3f",
                        zombie.getX(), zombie.getY(), zombie.getZ(), dh, OH.getDouble(ray), OH.getDouble(ray) - dh));
            }
            if (dh == 0.0F) {
                return;
            }
            Object shifted = RAY.newInstance(OX.getDouble(ray), OY.getDouble(ray), OH.getDouble(ray) - dh,
                    DX.getDouble(ray), DY.getDouble(ray), DH.getDouble(ray), NANOS.getLong(ray));
            original = ray;
            lowered = shifted;
            LAST.set(null, shifted);
        } catch (Throwable t) {
            restore();
            ViewpointBloodFxFix.disable("lowering the aim ray onto the terrain", t);
        }
    }

    public static void restore() {
        if (lowered == null) {
            return;
        }
        try {
            // the renderer may have saved a newer ray meanwhile
            if (LAST.get(null) == lowered) {
                LAST.set(null, original);
            }
        } catch (IllegalAccessException | RuntimeException e) {
        }
        original = null;
        lowered = null;
    }
}
