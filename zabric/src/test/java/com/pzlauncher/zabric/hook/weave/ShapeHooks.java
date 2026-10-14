package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.ModifyVariable;
import com.pzlauncher.zabric.hook.Self;
import com.pzlauncher.zabric.hook.Thrown;

import java.util.Locale;

/** Author code: the target shapes the glue binds differently. */
@Hooks(Shapes.class)
public final class ShapeHooks {
    private ShapeHooks() {
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    public static void created(@Self Shapes self, String name, CallbackInfo ci) {
        Events.add("created " + self.name);
    }

    @Inject(method = "count", at = @At("HEAD"))
    public static void beforeCount(String[] parts, CallbackInfoReturnable<Integer> cir) {
        Events.add("beforeCount " + String.join(",", parts));
    }

    @Inject(method = "parse", at = @At("RETURN"))
    public static void parsed(String text, CallbackInfoReturnable<Integer> cir) {
        Events.add("parsed " + cir.getReturnValue());
    }

    @Inject(method = "parse", at = @At("FINALLY"))
    public static void afterParse(String text, CallbackInfoReturnable<Integer> cir, @Thrown Throwable thrown) {
        Events.add("afterParse " + cir.getReturnValue() + " " + (thrown == null ? null
                : thrown.getClass().getSimpleName()));
    }

    // slot 4: this is 0, first 1, the long gap 2 and 3
    @ModifyVariable(method = "pick", at = @At("HEAD"), argsOnly = true, index = 4)
    public static String upperSecond(String second) {
        return second.toUpperCase(Locale.ROOT);
    }

    @ModifyVariable(method = "pick", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    public static String trimFirst(String first) {
        return first.strip();
    }
}
