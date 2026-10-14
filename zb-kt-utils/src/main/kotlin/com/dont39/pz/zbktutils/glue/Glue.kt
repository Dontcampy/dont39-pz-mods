package com.dont39.pz.zbktutils.glue

// Read at compile time by zb-kt-utils-processor, which generates the loader glue from them.

/**
 * Hooks on a method of another mod or the game, named by string. Each parameter has exactly one of the bindings below;
 * the hook gets a copy, so it cannot change what it binds. The hook function is public: the glue is Java.
 *
 * The glue, not the hook, gates on the module's `Feature.active()` and turns anything the hook throws into
 * `Feature.disable`, so a hook body is only its logic and never throws into the target. An [Exit] paired with an
 * [Enter] on the same target runs exactly when that call's [Enter] passed the gate, even if the feature was disabled
 * since, so it can undo what the [Enter] changed.
 */
@Target()
annotation class Hook {
  /**
   * Runs before the target. With [skipOn], returning true skips the target; gated off or failed, the target runs.
   * [always]: see [Exit.always].
   */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.BINARY)
  annotation class Enter(
    val className: String,
    val methodName: String,
    val skipOn: Boolean = false,
    val strictMatch: Boolean = false,
    val warmUp: Boolean = false,
    val always: Boolean = false,
  )

  /**
   * Runs after the target. With [onThrow] also when the target throws, so a restore always happens. With [always] it
   * also runs while the feature is not active, e.g. to undo a lasting install, and checks `active()` itself; only on a
   * hook without a partner on the same target.
   */
  @Target(AnnotationTarget.FUNCTION)
  @Retention(AnnotationRetention.BINARY)
  annotation class Exit(
    val className: String,
    val methodName: String,
    val onThrow: Boolean = true,
    val strictMatch: Boolean = false,
    val warmUp: Boolean = false,
    val always: Boolean = false,
  )

  /** The target's receiver. */
  @Target(AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.BINARY)
  annotation class This

  /** The target's argument at [index]. */
  @Target(AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.BINARY)
  annotation class Argument(val index: Int)

  /** The target's return value; [Exit] only. */
  @Target(AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.BINARY)
  annotation class Return

  /** What the target threw, null when it returned; [Exit] with [Exit.onThrow] only. */
  @Target(AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.BINARY)
  annotation class Thrown

  /** All of the target's arguments, boxed. */
  @Target(AnnotationTarget.VALUE_PARAMETER)
  @Retention(AnnotationRetention.BINARY)
  annotation class AllArguments
}

/** Exposes the function as the global Lua function [name]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class LuaGlobal(val name: String)
