package com.dont39.pz.zombiebuddykotlinutils.glue

import kotlin.reflect.KClass

// Read at compile time by zombiebuddy-kotlin-utils-processor (applied by the pz-feature convention plugin), which
// generates the ZombieBuddy glue in com.dont39.pz.allinone: `<Feature>Patches` for the hooks, `<Feature>Lua` for the
// Lua functions. The annotated function must be a member of a top-level `object`, or a top-level function, and not
// private or protected; the processor reports every rule it enforces as a compile error naming the function.

/**
 * Hooks on another mod's or the game's method, named by string (it is not on the classpath). Parameters of a hook
 * function carry exactly one of ZombieBuddy's parameter annotations (`Patch.Argument`, `This`, `Return`, `Thrown`,
 * `AllArguments`, `Local`), copied as is onto the generated advice; writable bindings (`readOnly = false`) are not
 * supported. At most one [Enter] and one [Exit] per target method per feature; an [Enter] and [Exit] of the same target
 * share one `@Patch`, so their [Enter.strictMatch] / [Enter.warmUp] must agree.
 */
@Target()
annotation class Hook {
  /**
   * Runs before [className].[methodName] (`@Patch.OnEnter`). Returns `Unit`, or `Boolean` with [skipOn]: `true` skips
   * the target method. [strictMatch] and [warmUp] are `@Patch`'s options of the same name.
   */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.BINARY)
  annotation class Enter(
    val className: String,
    val methodName: String,
    val skipOn: Boolean = false,
    val strictMatch: Boolean = false,
    val warmUp: Boolean = false,
  )

  /**
   * Runs after [className].[methodName] (`@Patch.OnExit`), returns `Unit`. Unlike ZombieBuddy's default it also runs
   * when the target throws ([onThrowable]), so a restore always happens (zombiebuddy-patching.md "Patch Classes").
   */
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

/**
 * Exposes the function to Lua as the global function [name] (`@LuaMethod(name, global = true)`). Lua globals are shared
 * by all mods: [name] starts with the mod's prefix (`Dont39AllInOne_`) and is unique in the build.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class LuaGlobal(val name: String)
