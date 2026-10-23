package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.comments.Comments;
import com.dont39.pz.fasterloading.settings.Fix;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.ModifyVariable;
import zombie.scripting.ScriptParser;

/** The game's script-format parser. */
@Hooks(ScriptParser.class)
public final class ScriptHooks {
    private ScriptHooks() {
    }

    // Quadratic in comments times length: about 2 s per launch and per join for the game's tileGeometry.txt alone.
    // The game's method still runs, on text it returns unchanged in one scan, so that other mods' advice on it (Loading
    // Progress times it) runs in either order; cancelling would skip the advice inside ours.
    @ModifyVariable(method = "stripComments(Ljava/lang/String;)Ljava/lang/String;", at = @At("HEAD"), argsOnly = true)
    public static String stripComments(String text) {
        return Session.on(Fix.STRIP_COMMENTS) ? Comments.strip(text) : text;
    }
}
