package com.pzlauncher.zabric.internal;

/**
 * A class that loads while a transformer of an agent runs on the same thread passes every transformer of that agent
 * by, the access widener included. The loader describes a class it retransforms by reflection, inside the
 * retransformation; calling this first loads what that reads where every transformer sees it.
 */
public final class MemberTypes {
    private MemberTypes() {
    }

    /** Loads the types in the signatures of {@code c}'s fields, methods and constructors, and its nest members. */
    public static void load(Class<?> c) {
        c.getDeclaredMethods();
        c.getDeclaredConstructors();
        c.getDeclaredFields();
        c.getNestMembers();
    }
}
