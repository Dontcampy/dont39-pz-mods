package com.pzlauncher.zabric.hook;

/**
 * Passed to an {@link Inject} handler on a void target (Mixin's {@code CallbackInfo}); one per handler call. The
 * generated glue creates it.
 */
public class CallbackInfo {
    private final String id;
    private final boolean cancellable;
    private boolean cancelled;

    /** {@code id}: the target method's name. */
    public CallbackInfo(String id, boolean cancellable) {
        this.id = id;
        this.cancellable = cancellable;
    }

    /** The target method's name. */
    public String getId() {
        return id;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * At {@code HEAD}: the target does not run, nor do the handlers after this one. At {@code RETURN}: the handlers
     * after this one do not run. Throws when the handler's {@link Inject} is not {@link Inject#cancellable()}.
     */
    public void cancel() {
        if (!cancellable) {
            throw new IllegalStateException("cannot cancel " + id + ": the handler's @Inject is not cancellable");
        }
        cancelled = true;
    }
}
