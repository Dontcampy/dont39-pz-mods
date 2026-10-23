package com.dont39.pz.fasterloading.textures;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * One compressed level in native memory, {@code data}'s remaining bytes; freed by {@link #close}. Its 4×4 blocks
 * count a partial block at the right and bottom edge whole.
 */
public record Blocks(ByteBuffer data) implements AutoCloseable {
    /** The blocks a row of a {@code width} pixels wide level. */
    public static int columns(int width) {
        return (width + 3) / 4;
    }

    /** The block rows of a {@code height} pixels high level. */
    public static int rows(int height) {
        return (height + 3) / 4;
    }

    /** The blocks of a {@code width}×{@code height} level. */
    public static int count(int width, int height) {
        return columns(width) * rows(height);
    }

    @Override
    public void close() {
        MemoryUtil.memFree(data);
    }
}
