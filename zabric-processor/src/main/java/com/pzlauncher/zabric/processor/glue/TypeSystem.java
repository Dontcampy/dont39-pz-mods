package com.pzlauncher.zabric.processor.glue;

import java.util.List;

/** A front end's types as {@link HookAnalyzer} needs them; the front end presents them as the JVM sees them. */
public interface TypeSystem<T> {
    /** Canonical name of the erasure as Java source, e.g. {@code java.lang.String[]}, {@code a.b.Outer.Inner}. */
    String erasedName(T type);

    /** JVM descriptor of the erasure, e.g. {@code La/b/Outer$Inner;}. */
    String descriptor(T type);

    /** Java source with type arguments, without type annotations. */
    String source(T type);

    List<T> typeArguments(T type);

    /** Null for a non-array type. */
    T component(T type);

    /** Assignability of the erasures as ByteBuddy's {@code isAssignableTo}: no primitive widening or boxing. */
    boolean isSubtype(T sub, T sup);

    /** Why code in package {@code pkg} cannot name {@code type}'s erasure, or null when it can. */
    String accessProblem(T type, String pkg);
}
