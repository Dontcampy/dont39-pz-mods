package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.settings.Fix;
import com.dont39.pz.fasterloading.textures.Dxt5Encoder;
import com.dont39.pz.fasterloading.textures.Upload;
import com.pzlauncher.zabric.hook.At;
import com.pzlauncher.zabric.hook.CallbackInfo;
import com.pzlauncher.zabric.hook.Hooks;
import com.pzlauncher.zabric.hook.Inject;
import org.lwjgl.opengl.GL11;
import zombie.debug.DebugType;

import java.nio.ByteBuffer;

/** LWJGL's OpenGL 1.1 calls, where the game uploads its textures. */
@Hooks(GL11.class)
public final class TextureHooks {
    // its pool starts at the first level it splits
    private static final Dxt5Encoder ENCODER = new Dxt5Encoder(Runtime.getRuntime().availableProcessors());

    private static boolean reported;

    private TextureHooks() {
    }

    // The driver compresses each level inside this call on the render thread, about 20 ms a tile page; a join's packs
    // queue behind it. No argument change makes glTexImage2D take compressed blocks, so the hook uploads its own and
    // cancels the call; that skips other mods' advice inside ours, and nothing else on the live mod list patches GL11.
    @Inject(method = "glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"), cancellable = true)
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
            int format, int type, ByteBuffer pixels, CallbackInfo ci) {
        // the splash screen uploads before the game log and cache folder Session needs are up
        if (!Upload.requested(target, internalFormat, width, height, border, format, type, pixels)
                || !Session.on(Fix.TEXTURE_COMPRESSION) || !Upload.packedRows(width)) {
            return;
        }
        Upload.compressed(ENCODER, target, level, width, height, pixels);
        // after the first upload, so the line is not followed by that upload's failure
        if (!reported) {
            reported = true;
            DebugType.Mod.println(Session.PREFIX + "texture compression: %s on %d threads", ENCODER.id(),
                    ENCODER.threads());
        }
        ci.cancel();
    }
}
