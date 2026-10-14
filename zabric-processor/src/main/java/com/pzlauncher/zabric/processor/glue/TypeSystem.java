package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/**
 * What {@link HookAnalyzer} needs to know about a front end's types ({@code T}: javac's TypeMirror, KSP's type, ...).
 * Types here are Java types as the JVM sees them.
 */
public interface TypeSystem<T> {
    /**
     * The canonical name of the erasure, as Java source: {@code int}, {@code void}, {@code java.lang.String[]},
     * {@code a.b.Outer.Inner}.
     */
    String erasedName(T type);

    /** The JVM descriptor of the erasure, e.g. {@code I}, {@code [Ljava/lang/String;}, {@code La/b/Outer$Inner;}. */
    String descriptor(T type);

    /** Java source of the full type, type arguments included and type annotations left out. */
    String source(T type);

    /** The type arguments, empty for a raw or non-generic type. */
    List<T> typeArguments(T type);

    /** The component type of an array type, else null. */
    T component(T type);

    /**
     * Whether a value of {@code sub}'s erasure can be used as {@code sup}'s erasure without conversion: reference
     * widening only, a primitive only to itself (ByteBuddy's {@code isAssignableTo}).
     */
    boolean isSubtype(T sub, T sup);

    /** Why code in package {@code pkg} cannot name {@code type}'s erasure, or null when it can. */
    String accessProblem(T type, String pkg);
}
