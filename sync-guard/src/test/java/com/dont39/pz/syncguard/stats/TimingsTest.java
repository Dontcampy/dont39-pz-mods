package com.dont39.pz.syncguard.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingsTest {
    @Test
    void empty() {
        Timings t = new Timings();
        assertEquals(0, t.percentileNanos(0.99));
        assertEquals(0, t.maxNanos());
        assertEquals(0, t.meanNanos());
    }

    @Test
    void percentileIsTheBucketBoundAtMostTwiceTheValue() {
        Timings t = new Timings();
        for (int i = 0; i < 99; i++) {
            t.record(100_000);
        }
        t.record(5_000_000);
        long p99 = t.percentileNanos(0.99);
        assertTrue(p99 >= 100_000 && p99 <= 200_000, "p99 " + p99);
        assertEquals(5_000_000, t.percentileNanos(1.0));
        assertEquals(5_000_000, t.maxNanos());
        assertEquals(100, t.count());
    }

    @Test
    void neverAboveTheMax() {
        Timings t = new Timings();
        t.record(1_500_000);
        assertEquals(1_500_000, t.percentileNanos(0.5));
    }

    @Test
    void reset() {
        Timings t = new Timings();
        t.record(10);
        t.reset();
        assertEquals(0, t.count());
        assertEquals(0, t.maxNanos());
    }
}
