package com.dont39.pz.syncguard.stats;

import java.util.Arrays;

/**
 * Durations in power-of-two microsecond buckets: a percentile is the upper bound of its bucket, at most twice the true
 * value, which is enough to see whether a phase stays under a millisecond. Not thread-safe.
 */
public final class Timings {
    private static final int BUCKETS = 32;

    private final long[] counts = new long[BUCKETS];
    private long count;
    private long maxNanos;
    private long totalNanos;

    public void record(long nanos) {
        long micros = Math.max(0, nanos) / 1000;
        int bucket = micros == 0 ? 0 : Math.min(BUCKETS - 1, 64 - Long.numberOfLeadingZeros(micros));
        counts[bucket]++;
        count++;
        totalNanos += nanos;
        maxNanos = Math.max(maxNanos, nanos);
    }

    public long count() {
        return count;
    }

    public long maxNanos() {
        return maxNanos;
    }

    public long meanNanos() {
        return count == 0 ? 0 : totalNanos / count;
    }

    /** The bucket bound at or under which {@code fraction} of the durations fall; 0 without any. */
    public long percentileNanos(double fraction) {
        if (count == 0) {
            return 0;
        }
        long wanted = (long) Math.ceil(count * fraction);
        long seen = 0;
        for (int b = 0; b < BUCKETS; b++) {
            seen += counts[b];
            if (seen >= wanted) {
                return Math.min(maxNanos, upperMicros(b) * 1000);
            }
        }
        return maxNanos;
    }

    // bucket b holds [2^(b-1), 2^b) microseconds; bucket 0 under one
    private static long upperMicros(int bucket) {
        return 1L << bucket;
    }

    public void reset() {
        Arrays.fill(counts, 0);
        count = 0;
        maxNanos = 0;
        totalNanos = 0;
    }
}
