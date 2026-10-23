package com.dont39.pz.fasterloading.textures.bc7;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bc7EncoderTest {
    // every group the shader numbers y * MAX_GROUPS + x below count exists, and no row of the grid is all beyond it
    private static void covers(int count) {
        Bc7Encoder.Groups g = Bc7Encoder.groups(count);
        String at = count + " groups: " + g;
        assertTrue(g.x() >= 1 && g.x() <= Bc7Encoder.MAX_GROUPS, at);
        assertTrue(g.y() >= 1 && g.y() <= Bc7Encoder.MAX_GROUPS, at);
        assertTrue((long) g.x() * g.y() >= count, at);
        assertTrue((long) g.x() * (g.y() - 1) < count, at);
        if (g.y() > 1) {
            assertEquals(Bc7Encoder.MAX_GROUPS, g.x(), at);
        }
        long last = count - 1L;
        assertTrue(last / Bc7Encoder.MAX_GROUPS < g.y() && last % Bc7Encoder.MAX_GROUPS < g.x(), at);
    }

    @Test
    void blocksCountPartialBlocksWhole() {
        assertEquals(1, Bc7Encoder.blocks(1, 1));
        assertEquals(1, Bc7Encoder.blocks(4, 4));
        assertEquals(2, Bc7Encoder.blocks(5, 1));
        assertEquals(256 * 255, Bc7Encoder.blocks(1024, 1019));
        assertEquals(1024 * 1024, Bc7Encoder.blocks(4096, 4096));
    }

    @Test
    void groupsCoverEveryLevelFrom1x1To4096x4096() {
        for (int width = 1; width <= 4096; width++) {
            for (int height : new int[] {1, 2, 3, 4, 5, 7, 255, 1019, 1024, 2047, 2048, 4095, 4096}) {
                int blocks = Bc7Encoder.blocks(width, height);
                // one block a group (the mode 1/3/7 passes), four a group (the others)
                covers(blocks);
                covers((blocks + 3) / 4);
            }
        }
    }

    @Test
    void groupsAroundOneRowOfTheGrid() {
        for (int count : new int[] {1, 2, 65534, 65535, 65536, 65537, 2 * 65535, 2 * 65535 + 1, 1024 * 1024}) {
            covers(count);
        }
        assertEquals(new Bc7Encoder.Groups(65535, 1), Bc7Encoder.groups(65535));
        assertEquals(new Bc7Encoder.Groups(65535, 2), Bc7Encoder.groups(65536));
        assertEquals(new Bc7Encoder.Groups(65535, 17), Bc7Encoder.groups(1024 * 1024));
    }

    @Test
    void stripsAreWholeBlockRowsThatFitTheBuffers() {
        assertEquals(1024, Bc7Encoder.stripRows(1024, 1024));
        assertEquals(1019, Bc7Encoder.stripRows(1024, 1019));
        assertEquals(512, Bc7Encoder.stripRows(2048, 2048));
        assertEquals(256, Bc7Encoder.stripRows(4096, 4096));
        assertEquals(1, Bc7Encoder.stripRows(1, 1));
        // 1023 wide is 256 blocks a row, like 1024
        assertEquals(1024, Bc7Encoder.stripRows(1023, 1025));
        for (int width = 1; width <= 4096; width++) {
            for (int height : new int[] {1, 3, 4, 255, 1019, 1023, 1024, 1025, 2049, 4096}) {
                int rows = Bc7Encoder.stripRows(width, height);
                String at = width + "x" + height + ": " + rows;
                assertTrue(rows >= 1 && rows <= height, at);
                assertTrue(rows == height || rows % 4 == 0, at);
                assertTrue(Bc7Encoder.blocks(width, rows) <= Bc7Encoder.STRIP_BLOCKS, at);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Bc7Encoder.stripRows(4 * Bc7Encoder.STRIP_BLOCKS + 1, 4));
    }

    @Test
    void noGroupsIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Bc7Encoder.groups(0));
    }
}
