package com.dont39.pz.fasterloading.textures;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/** One compressed level in native memory, {@code data}'s remaining bytes; freed by {@link #close}. */
public record Blocks(ByteBuffer data) implements AutoCloseable {
    @Override
    public void close() {
        MemoryUtil.memFree(data);
    }
}
