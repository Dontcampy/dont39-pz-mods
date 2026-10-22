package com.dont39.pz.fasterloading.textures;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL21;

import java.nio.ByteBuffer;

/** Uploading a level the game asked the driver to compress, compressed by an {@link Encoder} instead. */
public final class Upload {
    private Upload() {
    }

    /**
     * Whether a {@code glTexImage2D} call asks the driver to compress tightly read RGBA bytes into a 2D level: what the
     * game's {@code TextureID.generateHwId} sends for a texture flagged for compression. Other calls are not ours.
     */
    public static boolean requested(int target, int internalFormat, int width, int height, int border, int format,
            int type, ByteBuffer pixels) {
        return target == GL11.GL_TEXTURE_2D && internalFormat == GL13.GL_COMPRESSED_RGBA && border == 0
                && format == GL11.GL_RGBA && type == GL11.GL_UNSIGNED_BYTE && width > 0 && height > 0
                && pixels != null && pixels.isDirect() && pixels.remaining() >= (long) width * height * 4;
    }

    /**
     * Whether the GL reads a {@code width} pixels wide RGBA level from client memory row after row from the start, with
     * no gap between rows, as {@link Encoder#encode} reads it.
     */
    public static boolean packedRows(int width) {
        return GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING) == 0
                && GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH) == 0
                && GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS) == 0
                && GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS) == 0
                && (width * 4) % GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT) == 0;
    }

    /**
     * Defines the bound texture's {@code level} from {@code encoder}'s blocks of {@code pixels}. Throws, naming the
     * level, when the GL lacks the encoder's format or reports an error for the upload.
     */
    public static void compressed(Encoder encoder, int target, int level, int width, int height, ByteBuffer pixels) {
        if (!GL.getCapabilities().GL_EXT_texture_compression_s3tc) {
            throw new IllegalStateException("the GL (" + GL11.glGetString(GL11.GL_RENDERER) + ", "
                    + GL11.glGetString(GL11.GL_VERSION) + ") has no GL_EXT_texture_compression_s3tc for "
                    + encoder.id());
        }
        try (Blocks blocks = encoder.encode(pixels, width, height)) {
            // the game's texture path never reads the flags (checkGLErrorThrowTexture is off), so one set by earlier
            // game code may still be pending and would read as ours; the game would not have looked at it either
            for (int i = 0; i < 8 && GL11.glGetError() != GL11.GL_NO_ERROR; i++) {
                // cleared
            }
            GL13.glCompressedTexImage2D(target, level, encoder.glInternalFormat(), width, height, 0, blocks.data());
        }
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            throw new IllegalStateException("the GL reported 0x" + Integer.toHexString(error) + " for a " + width + "x"
                    + height + " level " + level + " from " + encoder.id());
        }
    }
}
