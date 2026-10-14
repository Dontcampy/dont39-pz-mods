package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MixinExtras' {@code @ModifyReturnValue}:
 * {@code static R h(R original [, @Self T self] [, target parameters...] [, @Share(...) LocalRef<S> ...])}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface ModifyReturnValue {
    /** The target method, as {@link Inject#method()}. */
    String[] method();

    /** {@code RETURN} only. */
    At[] at() default @At("RETURN");
}
