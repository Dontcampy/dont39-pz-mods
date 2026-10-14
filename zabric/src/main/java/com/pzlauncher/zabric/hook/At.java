package com.pzlauncher.zabric.hook;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Where in the target method a handler runs. Supported values:
 * <ul>
 * <li>{@code "HEAD"}: before the target's body. Not on constructors.</li>
 * <li>{@code "RETURN"}: after the target returns normally; not when it throws or a HEAD handler cancelled it.</li>
 * <li>{@code "FINALLY"} (Zabric only, {@link Inject} only): after the target returns, throws or was cancelled, so a
 *     handler can undo what a HEAD handler changed. Not on constructors.</li>
 * </ul>
 * Mixin's other injection points (INVOKE, FIELD, NEW, TAIL, ...) are not supported.
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface At {
    String value();
}
