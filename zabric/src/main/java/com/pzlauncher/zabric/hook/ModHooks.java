package com.pzlauncher.zabric.hook;

/**
 * One mod's hooks, as the generated glue sees them; implemented by Zabric only. Open while the mod is in the last mod
 * list Zabric processed and has not stopped. A mod Zabric keeps preloaded opens at preLaunch, before the first mod
 * list, and with launch-wide hooks stays open on every mod list ({@code Lifecycle}).
 */
public interface ModHooks {
    String modId();

    /** Checked by the glue of {@link Inject#always()} handlers. */
    boolean open();

    /** {@link #open()} and {@code gate.active()}; never throws, a throwing gate counts as inactive. */
    boolean active(HookGate gate);

    /** The gate of hook classes that name none: its first failure stops all of the mod's hooks for the session. */
    HookGate defaultGate();

    /** Never throws; a gate that throws here stops the mod's hooks. */
    void failed(HookGate gate, String what, Throwable t);
}
