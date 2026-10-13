package com.dont39.pz.zbkt;

import com.dont39.pz.zabric.preload.Preload;

/**
 * Asks Zabric to keep this mod a ZombieBuddy preload mod. ZombieBuddy calls the mods' Main in the player's mod list
 * order, so Zabric's jar may not be on the classpath yet; the request then waits in a system property for Zabric's Main.
 */
final class PreloadRequest {
    private static final String PRELOAD = "com.dont39.pz.zabric.preload.Preload";

    private PreloadRequest() {
    }

    static void request(String modId, boolean preloadedThisLaunch) {
        if (zabricLoaded()) {
            Direct.register(modId, preloadedThisLaunch);
        } else {
            // a compile-time constant, inlined: no link to Zabric
            System.setProperty(Preload.REQUEST_PREFIX + modId, Boolean.toString(preloadedThisLaunch));
        }
    }

    private static boolean zabricLoaded() {
        try {
            Class.forName(PRELOAD, false, PreloadRequest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // the only class that links against Zabric, loaded only once Zabric is there
    private static final class Direct {
        static void register(String modId, boolean preloadedThisLaunch) {
            Preload.register(modId, preloadedThisLaunch);
        }
    }
}
