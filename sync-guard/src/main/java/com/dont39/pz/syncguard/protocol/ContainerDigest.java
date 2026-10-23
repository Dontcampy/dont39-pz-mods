package com.dont39.pz.syncguard.protocol;

/**
 * A container's digest: over each item's id, full type and item digest, in any order, as the two sides list a
 * container's items in different orders.
 */
public final class ContainerDigest {
    private final Unordered items = new Unordered();

    public ContainerDigest add(int id, String fullType, long itemDigest) {
        items.add(new Digest().putInt(id).putString(fullType).putLong(itemDigest).get());
        return this;
    }

    public long get() {
        return items.get();
    }
}
