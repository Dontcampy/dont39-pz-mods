package com.pzlauncher.zabric.hook;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lets a class keep a patch written directly against the loader (ZombieBuddy's {@code @Patch}); Zabric's annotation
 * processor rejects such a class without this marker and warns on every class with it.
 * <p>
 * <b>Not supported, not recommended.</b> Such a patch is plain loader code: Zabric does not gate it, does not catch
 * what it throws, does not check its target and does not close it when its mod leaves the mod list. Zabric takes no
 * responsibility when it breaks, e.g. when Zabric moves to another loader. Hook through {@link Hooks} instead.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface UnsafeLoaderPatch {
}
