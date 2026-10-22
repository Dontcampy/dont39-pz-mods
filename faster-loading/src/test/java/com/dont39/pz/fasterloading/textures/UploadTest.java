package com.dont39.pz.fasterloading.textures;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadTest {
    private static final ByteBuffer PIXELS = ByteBuffer.allocateDirect(8 * 4 * 4);

    private static boolean requested(int target, int internalFormat, int border, int format, int type,
            ByteBuffer pixels) {
        return Upload.requested(target, internalFormat, 8, 4, border, format, type, pixels);
    }

    @Test
    void theGamesCompressedUploadIsOurs() {
        assertTrue(requested(GL11.GL_TEXTURE_2D, GL13.GL_COMPRESSED_RGBA, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                PIXELS));
    }

    @Test
    void otherUploadsAreNot() {
        int t = GL11.GL_TEXTURE_2D;
        int c = GL13.GL_COMPRESSED_RGBA;
        int f = GL11.GL_RGBA;
        int u = GL11.GL_UNSIGNED_BYTE;
        assertFalse(requested(t, GL11.GL_RGBA, 0, f, u, PIXELS), "not asked to compress");
        assertFalse(requested(t, GL11.GL_RGBA8, 0, f, u, PIXELS));
        assertFalse(requested(GL30.GL_TEXTURE_2D_ARRAY, c, 0, f, u, PIXELS), "another target");
        assertFalse(requested(t, c, 1, f, u, PIXELS), "a border");
        assertFalse(requested(t, c, 0, GL30.GL_BGRA, u, PIXELS), "another layout");
        assertFalse(requested(t, c, 0, f, GL11.GL_FLOAT, PIXELS), "another type");
        assertFalse(requested(t, c, 0, f, u, null), "no client data");
        assertFalse(requested(t, c, 0, f, u, ByteBuffer.allocate(8 * 4 * 4)), "a heap buffer");
        assertFalse(requested(t, c, 0, f, u, ByteBuffer.allocateDirect(8 * 4 * 4 - 1)), "too few bytes");
        assertFalse(Upload.requested(t, c, 0, 4, 0, f, u, PIXELS), "no size");
    }
}
