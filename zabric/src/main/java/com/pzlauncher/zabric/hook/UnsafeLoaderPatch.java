package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Allows a ZombieBuddy {@code @Patch} class, which is otherwise a compile error. <b>Not supported, not recommended</b>:
 * Zabric does not gate, catch or check such a patch and takes no responsibility when it breaks, e.g. on a loader
 * switch; use {@link Hooks}.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface UnsafeLoaderPatch {
}
