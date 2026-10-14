package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler that replaces an argument of the target before its body runs (Mixin's {@code @ModifyVariable} with
 * {@code at = @At("HEAD"), argsOnly = true}; Mixin's {@code @ModifyArg} changes an argument of a call inside the
 * method, which is not supported). Signature:
 * {@code static P h(P original [, @Self T self] [, target parameters...] [, @Share(...) LocalRef<S> ...])}, returning
 * the new value.
 * <p>
 * The argument, as in Mixin: {@link #index()} is its local variable slot ({@code this} takes slot 0 of an instance
 * method, {@code long} and {@code double} take two); else {@link #ordinal()} counts the arguments of type {@code P};
 * with neither, the target has exactly one argument of type {@code P}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ModifyVariable {
    /** The target method, as {@link Inject#method()}. */
    String[] method();

    /** {@code HEAD} only. */
    At at();

    /** Must be true: only arguments can be modified. */
    boolean argsOnly() default false;

    /** The argument's local variable slot, or -1. */
    int index() default -1;

    /** Among the target's arguments of the handler's type, which one, or -1. */
    int ordinal() default -1;
}
