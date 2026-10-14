package com.pzlauncher.zabric.hook;

/**
 * The hooks of one mod, as the generated glue sees them ({@link ZabricHooks#mod}). Implemented by Zabric only.
 * <p>
 * A mod's hooks are open while the mod is in the mod list Zabric last processed (its main entrypoints run then), the
 * targets of its default-gate hook classes matched, and its default gate has not failed. They open once Zabric checked
 * that the loader wove them into the targets already loaded. Closed before the first mod list loads, after a target
 * mismatch or default-gate failure (for the session), and while the mod is not in the list.
 */
public interface ModHooks {
    String modId();

    /** Whether the mod's hooks are open; checked by the glue of {@link Inject#always()} handlers. */
    boolean open();

    /** {@link #open()} and {@code gate.active()}; never throws: a throwing gate is reported to it and counts as false. */
    boolean active(HookGate gate);

    /**
     * The gate of hook classes that name none: always active while {@link #open()}; on the first failure it closes all
     * of the mod's hooks for the session and logs which handler failed on which target.
     */
    HookGate defaultGate();

    /**
     * Reports a handler's failure to {@code gate}; never throws. If the gate itself throws, the mod's hooks stop as on
     * a default-gate failure.
     */
    void failed(HookGate gate, String what, Throwable t);
}
