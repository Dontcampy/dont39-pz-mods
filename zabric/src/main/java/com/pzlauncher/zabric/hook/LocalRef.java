package com.pzlauncher.zabric.hook;

/**
 * A value shared by the handlers of one call; see {@link Share}. Boxed for primitives. Starts as null on every call.
 */
public interface LocalRef<T> {
    T get();

    void set(T value);

    /** A new, empty reference; called by the generated glue once per call. */
    static <T> LocalRef<T> create() {
        return new SharedRef<>();
    }
}
