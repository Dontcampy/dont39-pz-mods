package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfoReturnable;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import com.pzlauncher.zabric.hook.ModifyReturnValue;
import com.pzlauncher.zabric.hook.ModifyVariable;

import java.util.Locale;

/** Author code: handlers on the mod's default gate. */
@Hooks(Target.class)
public final class DefaultHooks {
    private DefaultHooks() {
    }

    @ModifyVariable(method = "greet", at = @At("HEAD"), argsOnly = true)
    public static String trim(String name) {
        return name.strip();
    }

    @Inject(method = "greet", at = @At("RETURN"), cancellable = true)
    public static void shout(String name, CallbackInfoReturnable<String> cir) {
        if (name.equals("bob")) {
            cir.setReturnValue(cir.getReturnValue().toUpperCase(Locale.ROOT));
        }
    }

    @ModifyReturnValue(method = "compute")
    public static int plusOne(int original) {
        return original + 1;
    }
}
