package com.dont39.pz.fasterloading.textures.bc7;

import com.dont39.pz.fasterloading.textures.Blocks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bc7EncoderTest {
    // the grid, numbered row by row, holds every group below count in as few rows as fit, and fewer groups than rows
    // past it: each of those is dispatched for nothing
    private static void covers(int count) {
        Bc7Encoder.Groups g = Bc7Encoder.groups(count);
        String at = count + " groups: " + g;
        assertTrue(g.x() >= 1 && g.x() <= Bc7Encoder.MAX_GROUPS, at);
        assertTrue(g.y() >= 1 && g.y() <= Bc7Encoder.MAX_GROUPS, at);
        long total = (long) g.x() * g.y();
        assertTrue(total >= count, at);
        assertTrue(total - count < g.y(), at);
        assertEquals((count + Bc7Encoder.MAX_GROUPS - 1) / Bc7Encoder.MAX_GROUPS, g.y(), at);
    }

    @Test
    void blocksCountPartialBlocksWhole() {
        assertEquals(1, Blocks.count(1, 1));
        assertEquals(1, Blocks.count(4, 4));
        assertEquals(2, Blocks.count(5, 1));
        assertEquals(256 * 255, Blocks.count(1024, 1019));
        assertEquals(1024 * 1024, Blocks.count(4096, 4096));
    }

    @Test
    void groupsCoverEveryLevelFrom1x1To4096x4096() {
        for (int width = 1; width <= 4096; width++) {
            for (int height : new int[] {1, 2, 3, 4, 5, 7, 255, 1019, 1024, 2047, 2048, 4095, 4096}) {
                int blocks = Blocks.count(width, height);
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
        assertEquals(new Bc7Encoder.Groups(1, 1), Bc7Encoder.groups(1));
        assertEquals(new Bc7Encoder.Groups(65535, 1), Bc7Encoder.groups(65535));
        // a 1024x1024 level's blocks, one a group: rows of 65535 put 65534 groups past them
        assertEquals(new Bc7Encoder.Groups(32768, 2), Bc7Encoder.groups(65536));
        assertEquals(new Bc7Encoder.Groups(32769, 2), Bc7Encoder.groups(65537));
        assertEquals(new Bc7Encoder.Groups(61681, 17), Bc7Encoder.groups(1024 * 1024));
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
                assertTrue(Blocks.count(width, rows) <= Bc7Encoder.STRIP_BLOCKS, at);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Bc7Encoder.stripRows(4 * Bc7Encoder.STRIP_BLOCKS + 1, 4));
    }

    @Test
    void noGroupsIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> Bc7Encoder.groups(0));
    }
}
