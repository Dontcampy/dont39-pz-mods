package com.dont39.pz.fasterloading.textures;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dxt5EncoderTest {
    private final Dxt5Encoder serial = new Dxt5Encoder(1);
    private final Dxt5Encoder parallel = new Dxt5Encoder(8);

    // a sprite-like image: colour gradients, premultiplied, with transparent surroundings and soft edges
    private static byte[] sprite(int width, int height, long seed) {
        Random random = new Random(seed);
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double dx = (x - width / 2.0) / (width / 2.0);
                double dy = (y - height / 2.0) / (height / 2.0);
                double r = Math.sqrt(dx * dx + dy * dy);
                int a = r < 0.7 ? 255 : r < 0.9 ? (int) (255 * (0.9 - r) / 0.2) : 0;
                int o = (y * width + x) * 4;
                int[] c = {x * 255 / Math.max(1, width - 1), y * 255 / Math.max(1, height - 1),
                        (x + y) % 64 * 4 + random.nextInt(3)};
                for (int i = 0; i < 3; i++) {
                    rgba[o + i] = (byte) ((Math.min(255, c[i]) * a + 127) / 255);
                }
                rgba[o + 3] = (byte) a;
            }
        }
        return rgba;
    }

    private static ByteBuffer direct(byte[] bytes, int position) {
        ByteBuffer buffer = MemoryUtil.memAlloc(position + bytes.length);
        buffer.position(position);
        buffer.put(bytes).position(position);
        return buffer;
    }

    private static byte[] bytes(Blocks blocks) {
        byte[] out = new byte[blocks.data().remaining()];
        blocks.data().get(blocks.data().position(), out);
        return out;
    }

    @Test
    void aSpriteDecodesCloseToItsSource() {
        byte[] source = sprite(256, 192, 1);
        ByteBuffer in = direct(source, 0);
        try (Blocks blocks = parallel.encode(in, 256, 192)) {
            double[] psnr = Dxt5Decoder.psnr(source, Dxt5Decoder.decode(blocks.data(), 256, 192));
            assertTrue(psnr[0] >= 38 && psnr[1] >= 44, "rgb " + psnr[0] + " dB, alpha " + psnr[1] + " dB");
        } finally {
            MemoryUtil.memFree(in);
        }
    }

    // the image grown to whole blocks by repeating its last column and row
    private static byte[] padded(byte[] rgba, int width, int height) {
        int w = Blocks.columns(width) * 4;
        int h = Blocks.rows(height) * 4;
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                System.arraycopy(rgba, (Math.min(y, height - 1) * width + Math.min(x, width - 1)) * 4, out,
                        (y * w + x) * 4, 4);
            }
        }
        return out;
    }

    // the tiny levels' colour is far from the source whatever pads them: the test image spans 0-255 across 3 pixels
    @Test
    void levelsOfAnySizeHaveTheBlockCountTheGlExpectsAndRepeatTheirEdges() {
        int[][] sizes = {{1, 1}, {2, 2}, {3, 5}, {4, 4}, {1019, 7}, {5, 1022}};
        for (int[] size : sizes) {
            int width = size[0];
            int height = size[1];
            byte[] source = sprite(width, height, 2);
            byte[] grown = padded(source, width, height);
            ByteBuffer in = direct(source, 0);
            ByteBuffer whole = direct(grown, 0);
            try (Blocks blocks = parallel.encode(in, width, height);
                    Blocks expected = parallel.encode(whole, Blocks.columns(width) * 4,
                            Blocks.rows(height) * 4)) {
                assertEquals(Blocks.count(width, height) * 16, blocks.data().remaining(),
                        width + "x" + height);
                assertArrayEquals(bytes(expected), bytes(blocks), width + "x" + height);
            } finally {
                MemoryUtil.memFree(in);
                MemoryUtil.memFree(whole);
            }
        }
    }

    @Test
    void theParallelOutputIsTheSerialOne() {
        byte[] source = sprite(1024, 1019, 3);
        ByteBuffer in = direct(source, 0);
        try (Blocks one = serial.encode(in, 1024, 1019); Blocks many = parallel.encode(in, 1024, 1019)) {
            assertArrayEquals(bytes(one), bytes(many));
        } finally {
            MemoryUtil.memFree(in);
        }
    }

    @Test
    void readingStartsAtTheBuffersPosition() {
        byte[] source = sprite(64, 64, 4);
        ByteBuffer at0 = direct(source, 0);
        ByteBuffer at12 = direct(source, 12);
        try (Blocks a = serial.encode(at0, 64, 64); Blocks b = serial.encode(at12, 64, 64)) {
            assertArrayEquals(bytes(a), bytes(b));
        } finally {
            MemoryUtil.memFree(at0);
            MemoryUtil.memFree(at12);
        }
    }

    @Test
    void tooFewBytesAreRefused() {
        ByteBuffer in = MemoryUtil.memAlloc(15);
        try {
            assertThrows(IllegalArgumentException.class, () -> serial.encode(in, 2, 2));
        } finally {
            MemoryUtil.memFree(in);
        }
    }
}
