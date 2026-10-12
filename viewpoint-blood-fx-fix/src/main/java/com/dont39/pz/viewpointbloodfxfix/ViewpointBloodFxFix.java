package com.dont39.pz.viewpointbloodfxfix;

import java.util.Set;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/**
 * Viewpoint 0.1.6 raises the near world by terrain relief on the GPU; BloodFX still draws at flat-ground height, so its
 * blood sinks into rising ground and floats over falling ground.
 */
public final class ViewpointBloodFxFix {
    public static final String NAME = "viewpoint-blood-fx-fix";
    private static final String PREFIX = "[Dont39AllInOne] " + NAME + ": ";

    // a newer BloodFX may handle relief itself
    static final Set<String> KNOWN_BLOODFX = Set.of("1.5.1");
    static final boolean FORCE = Boolean.getBoolean("dont39.viewpointbloodfxfix.force");

    private static volatile Boolean active;

    private ViewpointBloodFxFix() {
    }

    public static void init() {
        String why = problem();
        log(why == null ? "ready (BloodFX " + bloodFxVersion() + ")" : "idle: " + why);
    }

    static boolean active() {
        Boolean a = active;
        if (a == null) {
            String why = problem();
            a = why == null;
            active = a;
            log(a ? "active" : "inactive: " + why);
        }
        return a;
    }

    static void disable(String what, Throwable t) {
        active = Boolean.FALSE;
        DebugType.Mod.printException(t, PREFIX + "disabled after an error while " + what, LogSeverity.Error);
    }

    private static String problem() {
        String version = bloodFxVersion();
        if (version == null) {
            return "BloodFX (ViewpointBloodFX) is not loaded";
        }
        if (Relief.PROBLEM != null) {
            return "this Viewpoint has no terrain relief API (" + Relief.PROBLEM + ")";
        }
        if (!KNOWN_BLOODFX.contains(version) && !FORCE) {
            return "BloodFX " + version + " is not a known version " + KNOWN_BLOODFX
                    + " (-Ddont39.viewpointbloodfxfix.force=true to apply anyway)";
        }
        return LiftOffsets.PROBLEM == null ? null : "unexpected BloodFX internals (" + LiftOffsets.PROBLEM + ")";
    }

    private static String bloodFxVersion() {
        try {
            return String.valueOf(Class.forName("bloodfx.Main").getField("VERSION").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static java.lang.reflect.Field debugField;

    /** BloodFX's own "Log blood particles to console.txt" option. */
    static boolean debug() {
        try {
            if (debugField == null) {
                debugField = LiftOffsets.field(Class.forName("bloodfx.BloodFx"), "debug");
            }
            return debugField.getBoolean(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    static void log(String message) {
        DebugType.Mod.println(PREFIX + message);
    }

    static void warn(String message) {
        DebugType.Mod.warn(PREFIX + message);
    }
}
