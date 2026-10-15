package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mixin's {@code @ModifyVariable} at {@code HEAD} with {@code argsOnly = true}, replacing a target argument (Mixin's
 * {@code @ModifyArg} means a call-site argument):
 * {@code static P h(P original [, @Self T self] [, target parameters...] [, @Share(...) LocalRef<S> ...])}. With
 * neither {@link #index()} nor {@link #ordinal()}, the target must have exactly one argument of type {@code P}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ModifyVariable {
    /** The target method, as {@link Inject#method()}. */
    String[] method();

    /** {@code HEAD} only. */
    At at();

    /** Must be true. */
    boolean argsOnly() default false;

    /** The argument's local variable slot, or -1. */
    int index() default -1;

    /** Which of the target's arguments of type {@code P}, or -1. */
    int ordinal() default -1;
}
