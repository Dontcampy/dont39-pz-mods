package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A hook class (Mixin's {@code @Mixin}): a final class with static handlers, or a Kotlin top-level object. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Hooks {
    Class<?>[] value() default {};

    /** Target classes by binary name, for those not nameable as class literals. */
    String[] targets() default {};

    /**
     * A gate with a {@code public static final INSTANCE}, or a Kotlin object; omitted, the mod's
     * {@link ModHooks#defaultGate()}.
     */
    Class<? extends HookGate> gate() default HookGate.class;
}
