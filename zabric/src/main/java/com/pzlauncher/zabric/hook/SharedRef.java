package com.pzlauncher.zabric.hook;

/** Confined to one call on one thread. */
final class SharedRef<T> implements LocalRef<T> {
    private T value;

    @Override
    public T get() {
        return value;
    }

    @Override
    public void set(T value) {
        this.value = value;
    }
}
