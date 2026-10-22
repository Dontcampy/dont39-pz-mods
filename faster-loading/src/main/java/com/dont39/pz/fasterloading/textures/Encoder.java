package com.dont39.pz.fasterloading.textures;

import java.nio.ByteBuffer;

/**
 * Compresses one texture level for the GL in place of the driver. Another encoder (another format, one on the GPU, or
 * a cache in front of one) implements this and the hook stays as it is.
 */
public interface Encoder {
    /** The compressed internal format of its blocks, as {@code glCompressedTexImage2D} takes it. */
    int glInternalFormat();

    /** Its name and version: what its output depends on besides the pixels, e.g. for a cache key. */
    String id();

    /**
     * The blocks of {@code width}×{@code height} tightly packed RGBA bytes starting at {@code rgba}'s position. Called
     * on the GL thread with the context current; the caller closes the result after the upload.
     */
    Blocks encode(ByteBuffer rgba, int width, int height);
}
