package com.pzlauncher.zabric.hook;

/** Mixin's {@code CallbackInfo}, for an {@link Inject} handler on a void target; created by the glue per call. */
public class CallbackInfo {
    private final String id;
    private final boolean cancellable;
    private boolean cancelled;

    public CallbackInfo(String id, boolean cancellable) {
        this.id = id;
        this.cancellable = cancellable;
    }

    public String getId() {
        return id;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Skips the target (at {@code HEAD}) and later handlers; throws unless {@link Inject#cancellable()}. */
    public void cancel() {
        if (!cancellable) {
            throw new IllegalStateException("cannot cancel " + id + ": the handler's @Inject is not cancellable");
        }
        cancelled = true;
    }
}
