package com.pzlauncher.zabric.hook;

/**
 * Passed to an {@link Inject} handler on a non-void target (Mixin's {@code CallbackInfoReturnable}); one per handler
 * call. {@code R} is the boxed return type. The generated glue creates it and reads the value back through the
 * primitive getters, which turn null into the type's default, as Mixin's do.
 */
public final class CallbackInfoReturnable<R> extends CallbackInfo {
    private R returnValue;

    /** At {@code HEAD}: no return value yet. */
    public CallbackInfoReturnable(String id, boolean cancellable) {
        super(id, cancellable);
    }

    /** At {@code RETURN} and {@code FINALLY}: the value the target returns so far. */
    public CallbackInfoReturnable(String id, boolean cancellable, R returnValue) {
        super(id, cancellable);
        this.returnValue = returnValue;
    }

    /**
     * Cancels ({@link #cancel()}) with this value: at {@code HEAD} the target returns it without running, at
     * {@code RETURN} it replaces the return value.
     */
    public void setReturnValue(R returnValue) {
        cancel();
        this.returnValue = returnValue;
    }

    /** The value the target returns so far; null at {@code HEAD} until set, and when the target threw. */
    public R getReturnValue() {
        return returnValue;
    }

    public boolean getReturnValueZ() {
        return returnValue != null && (Boolean) returnValue;
    }

    public byte getReturnValueB() {
        return returnValue == null ? 0 : (Byte) returnValue;
    }

    public char getReturnValueC() {
        return returnValue == null ? 0 : (Character) returnValue;
    }

    public short getReturnValueS() {
        return returnValue == null ? 0 : (Short) returnValue;
    }

    public int getReturnValueI() {
        return returnValue == null ? 0 : (Integer) returnValue;
    }

    public long getReturnValueJ() {
        return returnValue == null ? 0 : (Long) returnValue;
    }

    public float getReturnValueF() {
        return returnValue == null ? 0 : (Float) returnValue;
    }

    public double getReturnValueD() {
        return returnValue == null ? 0 : (Double) returnValue;
    }
}
