package com.example.zabricsample.hooks;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.LocalRef;
import com.pzlauncher.zabric.hook.ModifyReturnValue;
import com.pzlauncher.zabric.hook.ModifyVariable;
import com.pzlauncher.zabric.hook.Share;
import com.pzlauncher.zabric.hook.Thrown;
import zombie.GameTime;

/** One handler of each kind on {@link GameTime}. With {@link SampleGate}'s defaults the game behaves as without them. */
@Hooks(value = GameTime.class, gate = SampleGate.class)
public final class GameTimeHooks {
    private GameTimeHooks() {
    }

    /** Longest {@code GameTime.update} so far; a real mod would log a periodic summary, never one line per frame. */
    public static volatile long slowestUpdateNanos;
    public static volatile int lastHour = -1;

    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    public static void beforeUpdate(CallbackInfo ci, @Share("start") LocalRef<Long> start) {
        start.set(System.nanoTime());
        if (SampleGate.INSTANCE.freezeClock) {
            ci.cancel();
        }
    }

    // FINALLY, not RETURN: the timer must stop when update throws or beforeUpdate cancelled it, too
    @Inject(method = "update", at = @At("FINALLY"))
    public static void afterUpdate(CallbackInfo ci, @Thrown Throwable thrown, @Share("start") LocalRef<Long> start) {
        long took = System.nanoTime() - start.get();
        if (took > slowestUpdateNanos) {
            slowestUpdateNanos = took;
        }
    }

    @Inject(method = "getHour", at = @At("RETURN"))
    public static void afterGetHour(CallbackInfoReturnable<Integer> cir) {
        lastHour = cir.getReturnValueI();
    }

    @ModifyReturnValue(method = "getNightTint")
    public static float nightTint(float original) {
        return original * SampleGate.INSTANCE.nightTintScale;
    }

    @ModifyVariable(method = "getMultiplierFromTimeDelta", at = @At("HEAD"), argsOnly = true)
    public static float capTimeDelta(float timeDelta) {
        return Math.min(timeDelta, SampleGate.INSTANCE.maxTimeDelta);
    }
}
