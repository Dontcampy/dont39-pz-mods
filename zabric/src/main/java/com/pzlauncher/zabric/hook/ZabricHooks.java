package com.pzlauncher.zabric.hook;

import com.pzlauncher.zabric.hook.impl.HookListing;
import com.pzlauncher.zabric.hook.impl.HookRuntime;

/** Entry points of the hook runtime. */
public final class ZabricHooks {
    private ZabricHooks() {
    }

    /** The hooks of {@code modId}; the generated glue keeps it in a static final field. Never throws. */
    public static ModHooks mod(String modId) {
        return HookRuntime.mod(modId);
    }

    /**
     * Why the targets of {@code hookClass} do not match what it was compiled against (a class, method or descriptor is
     * missing), or null when they match. Zabric checks the default-gate hook classes of every mod in the mod list
     * itself; a named gate calls this (or {@link #gateProblem}) for its own hook classes before it turns active. Reads
     * the listing the processor wrote next to {@code hookClass}, from that class's own jar: all mod jars share one class
     * loader, so a resource lookup could find another mod's.
     */
    public static String problem(Class<?> hookClass) {
        return HookListing.problem(hookClass);
    }

    /**
     * {@link #problem} for every hook class whose listing names {@code gate} as its gate, read from the jar (or class
     * directory) of {@code gate} itself, so a gate shipped with its hook classes checks them all at once. Null when
     * they match, or when that jar has no hook class on {@code gate}. Never throws.
     */
    public static String gateProblem(Class<? extends HookGate> gate) {
        return HookListing.gateProblem(gate);
    }
}
