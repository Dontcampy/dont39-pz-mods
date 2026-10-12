package com.dont39.pz.viewpointbloodfxfix;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Lift is measured from the ground under the camera, so it is about zero at the player's feet.
 */
final class Relief {
    static final String PROBLEM;
    private static final MethodHandle CURRENT; // () -> Object
    private static final MethodHandle SCENE;   // (Object lift, double x, double y) -> float

    static {
        MethodHandle current = null, scene = null;
        String problem = null;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> lift = Class.forName("viewpoint.render.FarRelief$Lift");
            current = lookup.findStatic(Class.forName("viewpoint.far.Relief"), "current", MethodType.methodType(lift))
                    .asType(MethodType.methodType(Object.class));
            scene = lookup.findVirtual(lift, "scene", MethodType.methodType(float.class, double.class, double.class))
                    .asType(MethodType.methodType(float.class, Object.class, double.class, double.class));
        } catch (ReflectiveOperationException | LinkageError e) {
            problem = e.toString();
        }
        CURRENT = current;
        SCENE = scene;
        PROBLEM = problem;
    }

    private Relief() {
    }

    /**
     * Null when terrain relief is off.
     */
    static Object current() throws Throwable {
        return (Object) CURRENT.invokeExact();
    }

    static float at(Object lift, double x, double y) throws Throwable {
        return (float) SCENE.invokeExact(lift, x, y);
    }
}
