package com.pzlauncher.zabric.entrypoint;

/**
 * Called once per session after the loader's pass over the mod list in which it approved and loaded the mod; the class
 * is named by the {@code Zabric-Main} attribute of the mod's jar manifest.
 */
public interface MainEntrypoint {
    void onMain();
}
