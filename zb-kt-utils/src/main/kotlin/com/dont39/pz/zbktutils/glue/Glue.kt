package com.dont39.pz.zbktutils.glue

import kotlin.reflect.KClass

// Read at compile time by zb-kt-utils-processor, which generates the ZombieBuddy glue from them.

/** Hooks on a method of another mod or the game, named by string. Parameters use ZombieBuddy's `Patch.*` bindings. */
@Target()
annotation class Hook {
  /** `@Patch.OnEnter`. With [skipOn], returning true skips the target. */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.BINARY)
  annotation class Enter(
    val className: String,
    val methodName: String,
    val skipOn: Boolean = false,
    val strictMatch: Boolean = false,
    val warmUp: Boolean = false,
  )

  /** `@Patch.OnExit`. Unlike ZombieBuddy's default it also runs when the target throws, so a restore always happens. */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.BINARY)
  annotation class Exit(
    val className: String,
    val methodName: String,
    val onThrowable: KClass<out Throwable> = Throwable::class,
    val strictMatch: Boolean = false,
    val warmUp: Boolean = false,
  )
}

/** Exposes the function as the global Lua function [name]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class LuaGlobal(val name: String)
