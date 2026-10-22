package com.dont39.pz.fasterloading.textures;

import org.junit.jupiter.api.Test;
import org.lwjgl.stb.STBDXT;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dxt5AlphaTest {
    private static int[] block(int... values) {
        int[] alpha = new int[16];
        for (int i = 0; i < 16; i++) {
            alpha[i] = values[i % values.length];
        }
        return alpha;
    }

    private static void exact(int[] alpha) {
        assertEquals(0, Dxt5Alpha.error(Dxt5Alpha.encode(alpha), alpha), Arrays.toString(alpha));
    }

    @Test
    void flatBlocksAreExact() {
        exact(block(0));
        exact(block(255));
        exact(block(128));
    }

    @Test
    void blocksOfTwoValuesAreExact() {
        exact(block(0, 255));
        exact(block(10, 200));
        exact(block(255, 37));
    }

    @Test
    void fullyTransparentAndOpaquePixelsAroundOneValueAreExact() {
        // the 6-value mode's free 0 and 255, which sprite edges are full of
        exact(block(0, 255, 100));
        exact(block(0, 0, 255, 90, 160));
    }

    @Test
    void rampsAndEdgesAreCloseToTheBestBlock() {
        Random random = new Random(1);
        for (int n = 0; n < 40; n++) {
            int[] alpha = randomBlock(random, n % 2);
            long ours = Dxt5Alpha.error(Dxt5Alpha.encode(alpha), alpha);
            long best = bruteForce(alpha);
            assertTrue(ours <= best + best / 5 + 2, "ours " + ours + ", best " + best + ": " + Arrays.toString(alpha));
        }
    }

    // the search settles in a local minimum on some noise blocks, up to about twice the best error; noise is rare in
    // the game's sprites, and a wider search would cost every block
    @Test
    void noiseIsCloseToTheBestBlocksOverall() {
        Random random = new Random(3);
        long ours = 0;
        long best = 0;
        for (int n = 0; n < 40; n++) {
            int[] alpha = randomBlock(random, 2);
            ours += Dxt5Alpha.error(Dxt5Alpha.encode(alpha), alpha);
            best += bruteForce(alpha);
        }
        assertTrue(ours <= best * 1.1, "ours " + ours + ", best " + best);
    }

    @Test
    void neverWorseThanStbOnRandomBlocks() {
        Random random = new Random(2);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long src = stack.nmalloc(16);
            long dst = stack.nmalloc(8);
            for (int n = 0; n < 2000; n++) {
                int[] alpha = randomBlock(random, random.nextInt(3));
                for (int i = 0; i < 16; i++) {
                    MemoryUtil.memPutByte(src + i, (byte) alpha[i]);
                }
                // BC4 is DXT5's alpha block
                STBDXT.nstb_compress_bc4_block(dst, src);
                long stb = MemoryUtil.memGetLong(dst);
                long ours = Dxt5Alpha.error(Dxt5Alpha.encode(alpha), alpha);
                assertTrue(ours <= Dxt5Alpha.error(stb, alpha), Arrays.toString(alpha));
            }
        }
    }

    // smooth ramps, hard edges with 0/255, and noise: what tile sprites hold
    private static int[] randomBlock(Random random, int kind) {
        int[] alpha = new int[16];
        int a = random.nextInt(256);
        int b = random.nextInt(256);
        for (int i = 0; i < 16; i++) {
            alpha[i] = switch (kind) {
                case 0 -> a + (b - a) * i / 15;
                case 1 -> random.nextInt(4) == 0 ? (random.nextBoolean() ? 0 : 255) : a + random.nextInt(9) - 4;
                default -> random.nextInt(256);
            };
            alpha[i] = Math.max(0, Math.min(255, alpha[i]));
        }
        return alpha;
    }

    private static long bruteForce(int[] alpha) {
        long best = Long.MAX_VALUE;
        for (int a0 = 0; a0 < 256; a0++) {
            for (int a1 = 0; a1 < 256; a1++) {
                best = Math.min(best, Dxt5Alpha.error(Dxt5Alpha.pack(a0, a1, alpha), alpha));
            }
        }
        return best;
    }
}
