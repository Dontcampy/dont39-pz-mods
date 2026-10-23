package com.dont39.pz.fasterloading.textures;

import java.nio.ByteBuffer;

/**
 * Compresses one texture level for the GL in place of the driver. Another encoder (another format, one on the GPU, or
 * a cache in front of one) implements this and the hook stays as it is.
 */
public interface Encoder {
    /** The compressed internal format of the levels it defines, as {@code glCompressedTexImage2D} takes it. */
    int glInternalFormat();

    /** Its name and version: what its output depends on besides the pixels, e.g. for a cache key. */
    String id();

    /**
     * Defines {@code level} of the texture bound to {@code target} from {@code width}×{@code height} tightly packed
     * RGBA bytes starting at {@code rgba}'s position, which the caller frees once this returns. Called on the GL
     * thread with the context current and no pixel unpack buffer bound ({@link Upload#packedRows}); leaves the GL state
     * it touched as it found it.
     */
    void define(int target, int level, int width, int height, ByteBuffer rgba);
}
