package com.pzlauncher.zabric.hook;

/** A value shared by the handlers of one call ({@link Share}); null at the start of each call. */
public interface LocalRef<T> {
    T get();

    void set(T value);

    static <T> LocalRef<T> create() {
        return new SharedRef<>();
    }
}
