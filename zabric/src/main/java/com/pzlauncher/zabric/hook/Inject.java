package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mixin's {@code @Inject}:
 * {@code static void h([@Self T self,] [target parameters...,] CallbackInfo ci [, @Thrown Throwable t]
 * [, @Share(...) LocalRef<S> ...])}, target parameters all or none.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Inject {
    /** {@code "name"}, or {@code "name(descriptor)"} when the name is overloaded; {@code "<init>"} for constructors. */
    String[] method();

    At[] at();

    /** Lets the handler cancel ({@link CallbackInfo#cancel()}); not at {@code FINALLY}. */
    boolean cancellable() default false;

    /**
     * Zabric only: runs while the gate is inactive too, e.g. to undo a change after it turned off; the handler checks
     * the gate itself.
     */
    boolean always() default false;
}
