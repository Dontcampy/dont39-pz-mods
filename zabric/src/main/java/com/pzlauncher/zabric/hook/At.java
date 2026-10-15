package com.pzlauncher.zabric.hook;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Where in the target a handler runs: {@code "HEAD"}, {@code "RETURN"} (normal return only) or {@code "FINALLY"}
 * (Zabric only, {@link Inject} only: also after a throw or cancel, to undo what HEAD changed). Constructors take
 * {@code "RETURN"} only; other Mixin injection points are a compile error.
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface At {
    String value();
}
