package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.LocalRef;
import com.pzlauncher.zabric.hook.Self;
import com.pzlauncher.zabric.hook.Share;
import com.pzlauncher.zabric.hook.Thrown;

/** Author code: handlers on a named gate. */
@Hooks(value = Target.class, gate = TestGate.class)
public final class GatedHooks {
    private GatedHooks() {
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    public static void negative(@Self Target self, int x, CallbackInfoReturnable<Integer> cir,
                                @Share("seen") LocalRef<Integer> seen) {
        if (self == null) {
            throw new AssertionError("no receiver");
        }
        seen.set(x);
        if (x < 0) {
            cir.setReturnValue(-1);
        }
    }

    @Inject(method = "compute", at = @At("RETURN"))
    public static void record(int x, CallbackInfoReturnable<Integer> cir, @Share("seen") LocalRef<Integer> seen) {
        Events.add("record " + seen.get() + " -> " + cir.getReturnValue());
    }

    @Inject(method = "ping", at = @At("HEAD"), cancellable = true)
    public static void skipPing(CallbackInfo ci) {
        if (TestGate.INSTANCE.skipPing) {
            ci.cancel();
        }
    }

    @Inject(method = "boom", at = @At("FINALLY"))
    public static void afterBoom(CallbackInfo ci, @Thrown Throwable thrown) {
        Events.add("afterBoom " + thrown.getMessage());
    }

    @Inject(method = "fail", at = @At("HEAD"))
    public static void throwing(int x, CallbackInfoReturnable<Integer> cir) {
        throw new IllegalStateException("handler failed on " + x);
    }

    @Inject(method = "run", at = @At("HEAD"))
    public static void beforeRun(Runnable body, CallbackInfo ci) {
        Events.add("beforeRun");
    }

    @Inject(method = "run", at = @At("FINALLY"))
    public static void afterRun(Runnable body, CallbackInfo ci, @Thrown Throwable thrown) {
        Events.add("afterRun");
    }
}
