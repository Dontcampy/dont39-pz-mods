package com.dont39.pz.syncguard.game;

import com.pzlauncher.zabric.hook.HookGate;
import com.pzlauncher.zabric.hook.ZabricHooks;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;

import static com.dont39.pz.syncguard.Mod.PREFIX;

/**
 * The gate of all the mod's hooks: shut until the main entrypoint decided, and for the rest of the session after the
 * first error. Its own gate, not the mod's default one, so the hooks stay shut while the access widener's list is not
 * in effect.
 */
public final class Gate implements HookGate {
    public static final Gate INSTANCE = new Gate();

    private volatile boolean on;
    private volatile boolean failed;
    private Runnable onFailure = () -> {
    };

    private Gate() {
    }

    @Override
    public boolean active() {
        return on;
    }

    /** Why the hook targets differ from the game the mod was built against, or null. */
    public static String targetProblem() {
        return ZabricHooks.gateProblem(Gate.class);
    }

    /** {@code onFailure} undoes what the side changed in the game; it runs once, on the first failure. */
    public synchronized void open(Runnable onFailure) {
        if (failed) {
            return;
        }
        this.onFailure = onFailure;
        on = true;
    }

    /** Disables the mod for the session from its own code, as a hook's error would. */
    public void disable(String what, Throwable t) {
        failed(what, t);
    }

    @Override
    public void failed(String what, Throwable t) {
        Runnable undo;
        synchronized (this) {
            boolean first = !failed;
            failed = true;
            on = false;
            if (!first) {
                DebugType.Mod.printException(t, PREFIX + "error while " + what + ", after it was disabled",
                        LogSeverity.Error);
                return;
            }
            undo = onFailure;
            onFailure = null;
        }
        DebugType.Mod.printException(t, PREFIX + "disabled for this session after an error while " + what,
                LogSeverity.Error);
        try {
            undo.run();
        } catch (Throwable u) {
            DebugType.Mod.printException(u, PREFIX + "error while undoing its changes after it was disabled",
                    LogSeverity.Error);
        }
    }
}
