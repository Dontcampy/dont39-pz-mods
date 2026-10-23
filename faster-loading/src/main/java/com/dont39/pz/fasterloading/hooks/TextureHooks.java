package com.dont39.pz.fasterloading.hooks;

import com.dont39.pz.fasterloading.settings.Fix;
import com.dont39.pz.fasterloading.textures.Encoder;
import com.dont39.pz.fasterloading.textures.Encoders;
import com.dont39.pz.fasterloading.textures.Upload;
import com.dont39.pz.fasterloading.textures.bc7.Bc7Encoder;
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
                || !Session.on(Fix.TEXTURE_COMPRESSION)) {
            return;
        }
        Encoder encoder = Encoders.session(() -> Session.on(Fix.TEXTURE_BC7),
                line -> DebugType.Mod.println(Session.PREFIX + "texture compression: %s", line));
        if (!Upload.packedRows(width)) {
            // the driver picks DXT5 for GL_COMPRESSED_RGBA, so a BC7 texture with one level from it is incomplete
            if (encoder instanceof Bc7Encoder) {
                throw new IllegalStateException("a " + width + "x" + height + " level " + level + " to compress comes"
                        + " with unpack state other than tightly packed rows from client memory, which " + encoder.id()
                        + " does not take and the driver would compress to another format than the texture's other"
                        + " levels");
            }
            return;
        }
        Upload.compressed(encoder, target, level, width, height, pixels);
        ci.cancel();
    }
}
