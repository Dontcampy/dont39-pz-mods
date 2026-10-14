package com.pzlauncher.zabric.hook;

import com.pzlauncher.zabric.hook.impl.HookListing;
import com.pzlauncher.zabric.hook.impl.HookRuntime;

public final class ZabricHooks {
    private ZabricHooks() {
    }

    /** Never throws. */
    public static ModHooks mod(String modId) {
        return HookRuntime.mod(modId);
    }

    /**
     * Why the targets of {@code hookClass} differ from what it was compiled against, or null. Zabric checks
     * default-gate hook classes itself; a named gate calls this before it turns active.
     */
    public static String problem(Class<?> hookClass) {
        return HookListing.problem(hookClass);
    }

    /** {@link #problem} for all hook classes on {@code gate} in {@code gate}'s own jar; null if none. Never throws. */
    public static String gateProblem(Class<? extends HookGate> gate) {
        return HookListing.gateProblem(gate);
    }
}
