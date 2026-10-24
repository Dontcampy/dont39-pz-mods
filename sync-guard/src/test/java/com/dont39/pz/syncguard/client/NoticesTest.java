package com.dont39.pz.syncguard.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NoticesTest {
    @Test
    void nothingToShow() {
        assertEquals(0, new Notices().due(0));
    }

    @Test
    void theFirstRepairShowsAtOnce() {
        Notices n = new Notices();
        n.repaired();
        assertEquals(1, n.due(123));
        assertEquals(0, n.due(124));
    }

    @Test
    void repairsInsideTheWindowAddUpToTheNextNotice() {
        Notices n = new Notices();
        n.repaired();
        assertEquals(1, n.due(0));
        for (int i = 0; i < 10; i++) {
            n.repaired();
            assertEquals(0, n.due(i * 100));
        }
        assertEquals(0, n.due(Notices.WINDOW_MS - 1));
        assertEquals(10, n.due(Notices.WINDOW_MS));
        assertEquals(0, n.due(Notices.WINDOW_MS + 1));
    }
}
