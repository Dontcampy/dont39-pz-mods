package com.pzlauncher.zabric.hook.weave;

import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.ModifyVariable;
import com.pzlauncher.zabric.hook.Self;

/** Author code: a nested target named by binary name, a package-private method, a receiver's fields. */
@Hooks(targets = "com.pzlauncher.zabric.hook.weave.Nested$Item")
public final class ItemHooks {
    private ItemHooks() {
    }

    // the shape of a join step that turns one failure into success
    @ModifyVariable(method = "setState", at = @At("HEAD"), argsOnly = true)
    public static Nested.State takeOver(Nested.State next, @Self Nested.Item self) {
        if (next == Nested.State.FAIL && "taken".equals(self.error)) {
            self.error = null;
            return Nested.State.READY;
        }
        return next;
    }
}
