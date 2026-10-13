package com.dont39.pz.zabric.entrypoint;

/**
 * Called once per launch after the loader has preloaded every jar and before the game's main method runs; the class is
 * named by the {@code Zabric-PreLaunch} attribute of the mod's jar manifest. Java only: no kotlin.* types, no game
 * classes and no game log. Keep what to report and report it from a {@link MainEntrypoint}.
 */
public interface PreLaunchEntrypoint {
    void onPreLaunch();
}
