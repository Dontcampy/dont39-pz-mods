package com.example.zabricsample.hooks;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ZabricHooks;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

/** A named gate: a failure switches off only its hook classes, not the whole mod. Fields stand in for mod options. */
public final class SampleGate implements HookGate {
    public static final SampleGate INSTANCE = new SampleGate();

    private static final String PREFIX = "[ZabricJavaHooksSample] ";

    public volatile boolean freezeClock;
    public volatile float nightTintScale = 1f;
    public volatile float maxTimeDelta = Float.MAX_VALUE;

    /** Null until the first call checks the targets. */
    private volatile Boolean targetsMatch;
    private volatile boolean failed;

    private SampleGate() {
    }

    @Override
    public boolean active() {
        if (failed) {
            return false;
        }
        Boolean match = targetsMatch;
        return match != null ? match : checkTargets();
    }

    // Zabric checks only default-gate hooks; a named gate checks its own before it turns active
    private synchronized boolean checkTargets() {
        if (targetsMatch == null) {
            String problem = ZabricHooks.gateProblem(SampleGate.class);
            if (problem != null) {
                DebugType.Mod.error(PREFIX + "hooks off: %s", problem);
            }
            targetsMatch = problem == null;
        }
        return targetsMatch;
    }

    @Override
    public void failed(String what, Throwable t) {
        failed = true;
        DebugType.Mod.printException(t, PREFIX + "hooks off after an error while " + what, LogSeverity.Error);
    }
}
