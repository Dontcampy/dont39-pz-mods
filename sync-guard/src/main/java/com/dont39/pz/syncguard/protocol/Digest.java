package com.dont39.pz.syncguard.protocol;

import java.nio.charset.StandardCharsets;

/**
 * A 64-bit hash over a sequence of values, the same on every JVM: both sides compare it. Not cryptographic; it only
 * has to tell states apart that differ by accident.
 */
public final class Digest {
    private static final long SEED = 0x5347_6469_6765_7374L;
    private static final long GOLDEN = 0x9E37_79B9_7F4A_7C15L;
    private static final long STEP = 0xBF58_476D_1CE4_E5B9L;

    private long h = SEED;
    private int count;

    /** SplitMix64's finalizer: every input bit reaches every output bit. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return z ^ (z >>> 31);
    }

    public Digest putLong(long v) {
        h = Long.rotateLeft(h ^ mix(v + GOLDEN), 29) * STEP + GOLDEN;
        count++;
        return this;
    }

    public Digest putInt(int v) {
        return putLong(v);
    }

    public Digest putBoolean(boolean v) {
        return putLong(v ? 1 : 0);
    }

    /** By bit pattern: both sides read the same float, so no tolerance; NaNs are one value. */
    public Digest putFloat(float v) {
        return putLong(Float.floatToIntBits(v));
    }

    /** Null and "" differ. */
    public Digest putString(String s) {
        if (s == null) {
            return putLong(-1);
        }
        return putBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public Digest putBytes(byte[] bytes) {
        return putBytes(bytes, 0, bytes.length);
    }

    public Digest putBytes(byte[] bytes, int offset, int length) {
        putLong(length);
        int i = offset;
        int end = offset + length;
        for (; i + Long.BYTES <= end; i += Long.BYTES) {
            long v = 0;
            for (int j = 0; j < Long.BYTES; j++) {
                v = (v << 8) | (bytes[i + j] & 0xFF);
            }
            putLong(v);
        }
        long tail = 0;
        for (; i < end; i++) {
            tail = (tail << 8) | (bytes[i] & 0xFF);
        }
        return putLong(tail);
    }

    public long get() {
        return mix(h ^ mix(count));
    }

    /** One value's hash, for {@link Unordered#add}. */
    public static long of(long... values) {
        Digest d = new Digest();
        for (long v : values) {
            d.putLong(v);
        }
        return d.get();
    }
}
