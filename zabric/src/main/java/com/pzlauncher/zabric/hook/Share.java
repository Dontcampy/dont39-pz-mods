package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@link LocalRef} shared by the handlers of one hook class on one target method during one call (MixinExtras'
 * {@code @Share}): a {@code HEAD} handler can leave a value for a {@code RETURN} or {@code FINALLY} handler of the same
 * call. Handlers with the same key share one reference; it starts empty on every call.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Share {
    /** The key. */
    String value();
}
