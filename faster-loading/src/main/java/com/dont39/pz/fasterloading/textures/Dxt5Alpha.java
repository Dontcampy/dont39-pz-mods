package com.dont39.pz.fasterloading.textures;

/**
 * DXT5 (BC3) alpha blocks. stb_dxt fits only the block's minimum and maximum, about 2 dB below the NVIDIA driver on
 * the game's tile pages; this tries both of the format's modes with refined endpoints and keeps the closer one.
 */
final class Dxt5Alpha {
    // a hill climb from the fitted endpoints settles within a few steps on real blocks
    private static final int MAX_STEPS = 8;
    // fixed point: exact enough that a value never rounds to the wrong step, ties aside, which are equally close
    private static final int STEP_BITS = 20;

    private Dxt5Alpha() {
    }

    /**
     * The 8 bytes of the block for 16 alpha values (0-255, row by row), as a little-endian long: the two endpoints,
     * then a 3-bit palette index per pixel.
     */
    static long encode(int[] alpha) {
        int min = 255;
        int max = 0;
        // the 6-value mode has 0 and 255 for free, so its endpoints only need to span the values in between
        int innerMin = 255;
        int innerMax = 0;
        for (int a : alpha) {
            min = Math.min(min, a);
            max = Math.max(max, a);
            if (a != 0 && a != 255) {
                innerMin = Math.min(innerMin, a);
                innerMax = Math.max(innerMax, a);
            }
        }
        // exact cases first: most of a tile page is flat or a sprite's 0/255 edge
        if (min == max) {
            // every index 0, a0
            return min | ((long) min << 8);
        }
        if (innerMin > innerMax) {
            return pack(0, 0, alpha);
        }
        Values values = new Values(alpha);
        int six = pair(innerMin, innerMax);
        long sixError = error(six, values, Long.MAX_VALUE);
        if (sixError == 0) {
            return pack(six, alpha);
        }
        int eight = pair(max, min);
        long eightError = error(eight, values, Long.MAX_VALUE);
        if (eightError > 0) {
            eight = fit(eight, eightError, values, true);
            eightError = error(eight, values, Long.MAX_VALUE);
        }
        if (eightError == 0) {
            return pack(eight, alpha);
        }
        six = fit(six, sixError, values, false);
        return pack(error(six, values, eightError) < eightError ? six : eight, alpha);
    }

    // endpoints as the block's low 16 bits; a0 > a1 selects the 8-value mode, a0 <= a1 the 6-value one
    private static int fit(int start, long startError, Values values, boolean eight) {
        int best = start;
        long bestError = startError;
        for (int round = 0; round < 2 && bestError > 0; round++) {
            int fitted = leastSquares(best, values, eight);
            long e = fitted < 0 ? Long.MAX_VALUE : error(fitted, values, bestError);
            if (e >= bestError) {
                // the next round would fit the same indices again
                break;
            }
            best = fitted;
            bestError = e;
        }
        int b0 = endpoint0(best);
        int b1 = endpoint1(best);
        // the last step's neighbours were no better than where it moved, so they cannot win this step
        int p0 = -2;
        int p1 = -2;
        for (int step = 0; step < MAX_STEPS && bestError > 0; step++) {
            int n0 = b0;
            int n1 = b1;
            long nextError = bestError;
            for (int d0 = -1; d0 <= 1; d0++) {
                for (int d1 = -1; d1 <= 1; d1++) {
                    int c0 = b0 + d0;
                    int c1 = b1 + d1;
                    if ((d0 | d1) == 0 || c0 < 0 || c0 > 255 || c1 < 0 || c1 > 255 || (c0 > c1) != eight
                            || (Math.abs(c0 - p0) <= 1 && Math.abs(c1 - p1) <= 1)) {
                        continue;
                    }
                    long e = error(c0, c1, values, nextError);
                    if (e < nextError) {
                        n0 = c0;
                        n1 = c1;
                        nextError = e;
                    }
                }
            }
            if (n0 == b0 && n1 == b1) {
                break;
            }
            p0 = b0;
            p1 = b1;
            b0 = n0;
            b1 = n1;
            bestError = nextError;
        }
        return pair(b0, b1);
    }

    // endpoints solving the least squares for the values' closest steps; -1 when they leave the mode
    private static int leastSquares(int endpoints, Values values, boolean eight) {
        int a0 = endpoint0(endpoints);
        int a1 = endpoint1(endpoints);
        int last = eight ? 7 : 5;
        int stepsPerValue = stepsPerValue(a0, a1, last);
        double aa = 0;
        double ab = 0;
        double bb = 0;
        double ax = 0;
        double bx = 0;
        for (int k = 0; k < values.size; k++) {
            int a = values.value[k];
            int step = step(a0, last, stepsPerValue, a);
            if (!eight && Math.min(a, 255 - a) < distance(a0, a1, last, step, a)) {
                // on the 6-value mode's fixed 0 or 255, which the endpoints do not move
                continue;
            }
            int n = values.count[k];
            // weight of a0
            double w = (double) (last - step) / last;
            double v = 1 - w;
            aa += n * w * w;
            ab += n * w * v;
            bb += n * v * v;
            ax += n * w * a;
            bx += n * v * a;
        }
        double det = aa * bb - ab * ab;
        if (Math.abs(det) < 1e-9) {
            return -1;
        }
        int f0 = clamp((int) Math.round((ax * bb - bx * ab) / det));
        int f1 = clamp((int) Math.round((bx * aa - ax * ab) / det));
        return (f0 > f1) == eight ? pair(f0, f1) : -1;
    }

    /*
     * The values between the endpoints are evenly spaced steps that decoders round without ties, so the closest one
     * is at the closest unrounded step and its distance is that step's distance, rounded: one step to find per value,
     * no palette to scan.
     */

    /** The block with the given endpoints and, per pixel, the index of the closest palette value. */
    static long pack(int a0, int a1, int[] alpha) {
        boolean eight = a0 > a1;
        int last = eight ? 7 : 5;
        int stepsPerValue = stepsPerValue(a0, a1, last);
        long block = a0 | ((long) a1 << 8);
        for (int i = 0; i < 16; i++) {
            int a = alpha[i];
            int step = step(a0, last, stepsPerValue, a);
            int index = step == 0 ? 0 : step == last ? 1 : step + 1;
            if (!eight && Math.min(a, 255 - a) < distance(a0, a1, last, step, a)) {
                index = a < 128 ? 6 : 7;
            }
            block |= (long) index << (16 + 3 * i);
        }
        return block;
    }

    private static long pack(int endpoints, int[] alpha) {
        return pack(endpoint0(endpoints), endpoint1(endpoints), alpha);
    }

    private static long error(int endpoints, Values values, long limit) {
        return error(endpoint0(endpoints), endpoint1(endpoints), values, limit);
    }

    /** Squared error of the closest block with the given endpoints, or any value from {@code limit} up. */
    private static long error(int a0, int a1, Values values, long limit) {
        boolean eight = a0 > a1;
        int last = eight ? 7 : 5;
        int stepsPerValue = stepsPerValue(a0, a1, last);
        long sum = 0;
        for (int k = 0; k < values.size && sum < limit; k++) {
            int a = values.value[k];
            int d = distance(a0, a1, last, step(a0, last, stepsPerValue, a), a);
            if (!eight) {
                d = Math.min(d, Math.min(a, 255 - a));
            }
            sum += values.count[k] * d * d;
        }
        return sum;
    }

    private static int stepsPerValue(int a0, int a1, int last) {
        return a0 == a1 ? 0 : (int) Math.round((double) (last << STEP_BITS) / (a1 - a0));
    }

    // the closest unrounded step to a, 0 at a0 and last at a1
    private static int step(int a0, int last, int stepsPerValue, int a) {
        int step = ((a - a0) * stepsPerValue + (1 << (STEP_BITS - 1))) >> STEP_BITS;
        return Math.max(0, Math.min(last, step));
    }

    // |rounded value of the step - a|, from last times the unrounded distance
    private static int distance(int a0, int a1, int last, int step, int a) {
        int scaled = Math.abs(step * (a1 - a0) - last * (a - a0));
        return last == 7 ? (scaled + 3) / 7 : (scaled + 2) / 5;
    }

    /** Squared error of the block against the values. */
    static long error(long block, int[] alpha) {
        int a0 = endpoint0(block);
        int a1 = endpoint1(block);
        long sum = 0;
        for (int i = 0; i < 16; i++) {
            int d = value(a0, a1, index(block, i)) - alpha[i];
            sum += (long) d * d;
        }
        return sum;
    }

    /** The decoded value of a palette index, rounded as decoders do. */
    static int value(int a0, int a1, int index) {
        if (index < 2) {
            return index == 0 ? a0 : a1;
        }
        if (a0 > a1) {
            return ((8 - index) * a0 + (index - 1) * a1 + 3) / 7;
        }
        return index == 6 ? 0 : index == 7 ? 255 : ((6 - index) * a0 + (index - 1) * a1 + 2) / 5;
    }

    private static int pair(int a0, int a1) {
        return a0 | (a1 << 8);
    }

    static int endpoint0(long block) {
        return (int) (block & 0xFF);
    }

    static int endpoint1(long block) {
        return (int) ((block >>> 8) & 0xFF);
    }

    static int index(long block, int pixel) {
        return (int) ((block >>> (16 + 3 * pixel)) & 7);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // the block's distinct values and how often each occurs: the search scores these, not the 16 pixels
    private static final class Values {
        final int[] value = new int[16];
        final int[] count = new int[16];
        int size;

        Values(int[] alpha) {
            for (int a : alpha) {
                int k = 0;
                while (k < size && value[k] != a) {
                    k++;
                }
                if (k == size) {
                    value[size++] = a;
                }
                count[k]++;
            }
        }
    }
}
