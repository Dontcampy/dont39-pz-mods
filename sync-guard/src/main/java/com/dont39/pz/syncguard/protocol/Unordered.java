package com.dont39.pz.syncguard.protocol;

/**
 * A hash of a multiset: the same whatever order the elements come in, as a container's items and a Lua table's pairs
 * differ in order between the two sides.
 */
public final class Unordered {
    private long sum;
    private long sumOfSquares;
    private int count;

    public Unordered add(long elementHash) {
        long m = Digest.mix(elementHash);
        sum += m;
        sumOfSquares += m * m;
        count++;
        return this;
    }

    public int count() {
        return count;
    }

    public long get() {
        return new Digest().putLong(count).putLong(sum).putLong(sumOfSquares).get();
    }
}
