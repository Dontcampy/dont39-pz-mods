package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Zabric only: what the target threw, null when it returned or was cancelled. A {@code Throwable} parameter of a
 * {@code FINALLY} handler; the throwable propagates as before.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Thrown {
}
