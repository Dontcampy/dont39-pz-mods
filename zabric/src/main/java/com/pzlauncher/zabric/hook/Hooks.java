package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a hook class: a final class whose static methods are handlers on methods of the target classes (Mixin's
 * {@code @Mixin}). In Kotlin, a top-level object.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Hooks {
    /** The target classes. */
    Class<?>[] value() default {};

    /** Target classes by binary name, for those that cannot be named as class literals. */
    String[] targets() default {};

    /**
     * The gate of this class's handlers: a {@link HookGate} with a {@code public static final INSTANCE} of its own
     * type, or a Kotlin object. Omitted ({@code HookGate.class}): the mod's default gate,
     * {@link ModHooks#defaultGate()}.
     */
    Class<? extends HookGate> gate() default HookGate.class;
}
