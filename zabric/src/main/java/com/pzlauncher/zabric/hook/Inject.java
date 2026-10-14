package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A handler that runs at a point of the target method (Mixin's {@code @Inject}). Signature:
 * {@code static void h([@Self T self,] [target parameters...,] CallbackInfo ci [, @Thrown Throwable t]
 * [, @Share(...) LocalRef<S> ...])}, with {@link CallbackInfoReturnable} for a non-void target. Target parameters are
 * all or none. {@link Thrown} is for {@code FINALLY} only.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Inject {
    /**
     * The target method: {@code "name"} when the name is unique in the target class, else {@code "name(descriptor)"}
     * with a JVM descriptor; {@code "<init>"} for constructors. Exactly one.
     */
    String[] method();

    /** Exactly one of {@code HEAD}, {@code RETURN}, {@code FINALLY}; see {@link At}. */
    At[] at();

    /**
     * The handler may call {@link CallbackInfo#cancel()} or {@link CallbackInfoReturnable#setReturnValue}; at
     * {@code HEAD} that skips the target, at {@code RETURN} it replaces the return value. Not at {@code FINALLY}.
     */
    boolean cancellable() default false;

    /**
     * Zabric only: runs while the gate is inactive too (not while the mod's hooks are closed), e.g. to undo a lasting
     * change after the gate turned off. The handler checks the gate itself. Only in a hook class whose other handlers
     * share no state with it.
     */
    boolean always() default false;
}
