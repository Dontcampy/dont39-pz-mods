package com.dont39.pz.loadingprogress.hooks;

import com.dont39.pz.loadingprogress.draw.Interim;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import org.lwjglx.opengl.Display;

import static com.dont39.pz.loadingprogress.hooks.Loading.PROGRESS;

/** The render thread's swaps and idle loop, for interim frames while ZombieBuddy patches a Java mod jar. */
@Hooks(Display.class)
public final class DisplayHooks {
    private DisplayHooks() {
    }

    // the render thread's swap of a frame the game thread handed over
    @Inject(method = "update(Z)V", at = @At("HEAD"))
    public static void frameSwaps(CallbackInfo ci) {
        Interim.frameSwaps();
    }

    // the render loop's idle path while it does not wait for the game thread's frames, and the end of each swap
    @Inject(method = "processMessages", at = @At("HEAD"))
    public static void idle(CallbackInfo ci) {
        Interim.idle(PROGRESS);
    }
}
