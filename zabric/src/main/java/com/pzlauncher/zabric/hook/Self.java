package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Zabric only: the target's receiver ({@code this}). Mixin handlers are instance methods merged into the target; these
 * handlers are static, so the receiver is a parameter. Not on static targets, nor at {@code HEAD} of a constructor.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface Self {
}
