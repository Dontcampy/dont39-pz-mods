package com.pzlauncher.zabric.hook;

/**
 * Mixin's {@code CallbackInfoReturnable}, for an {@link Inject} handler on a non-void target; {@code R} is boxed, and
 * the primitive getters read null as the type's default, as Mixin's do.
 */
public final class CallbackInfoReturnable<R> extends CallbackInfo {
    private R returnValue;

    public CallbackInfoReturnable(String id, boolean cancellable) {
        super(id, cancellable);
    }

    public CallbackInfoReturnable(String id, boolean cancellable, R returnValue) {
        super(id, cancellable);
        this.returnValue = returnValue;
    }

    /** {@link #cancel()} with this value as the target's result. */
    public void setReturnValue(R returnValue) {
        cancel();
        this.returnValue = returnValue;
    }

    /** Null at {@code HEAD} until set, and when the target threw. */
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
