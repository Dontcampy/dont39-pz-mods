package com.dont39.pz.fasterloading.textures.bc7;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

/**
 * The GL state {@link Bc7Encoder} changes on the game's render thread, saved before a level and restored after it:
 * the game binds its own shader storage buffers and programs and expects them where it left them. The pixel unpack
 * buffer is 0 before a level ({@link com.dont39.pz.fasterloading.textures.Encoder#define}), so it is put back to 0.
 */
final class GlState {
    private final int program;
    private final int generic;
    private final int[] indices;
    private final int[] buffers;
    private final long[] starts;
    private final long[] sizes;

    private GlState(int[] indices) {
        program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        generic = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        this.indices = indices.clone();
        buffers = new int[indices.length];
        starts = new long[indices.length];
        sizes = new long[indices.length];
        // each query waits for the driver's thread; an unbound index has no range
        for (int k = 0; k < indices.length; k++) {
            buffers[k] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, indices[k]);
            if (buffers[k] != 0) {
                starts[k] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, indices[k]);
                sizes[k] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, indices[k]);
            }
        }
    }

    /** The current program, the shader storage bindings at {@code indices} and generic. */
    static GlState save(int... indices) {
        return new GlState(indices);
    }

    /**
     * Puts back what {@link #save} read, every item even after one fails. With {@code pending}, the failure that ended
     * the encoder's work, its failures are added to that; otherwise the first is thrown.
     */
    void restore(Throwable pending) {
        Throwable first = pending;
        // each indexed binding also sets the generic one, so that goes last
        for (int k = 0; k < indices.length; k++) {
            try {
                if (sizes[k] == 0) {
                    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, indices[k], buffers[k]);
                } else {
                    GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, indices[k], buffers[k], starts[k], sizes[k]);
                }
            } catch (RuntimeException | Error e) {
                first = add(first, e);
            }
        }
        try {
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, generic);
        } catch (RuntimeException | Error e) {
            first = add(first, e);
        }
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        } catch (RuntimeException | Error e) {
            first = add(first, e);
        }
        try {
            GL20.glUseProgram(program);
        } catch (RuntimeException | Error e) {
            first = add(first, e);
        }
        if (first != pending) {
            if (first instanceof RuntimeException r) {
                throw r;
            }
            throw (Error) first;
        }
    }

    private static Throwable add(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }
}
