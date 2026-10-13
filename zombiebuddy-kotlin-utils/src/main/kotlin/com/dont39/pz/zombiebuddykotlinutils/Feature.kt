package com.dont39.pz.zombiebuddykotlinutils

import com.dont39.pz.zombiebuddykotlinutils.LoggerUtils.lzDebug
import com.dont39.pz.zombiebuddykotlinutils.LoggerUtils.lzInfo
import zombie.debug.DebugType
import zombie.debug.LogSeverity

/**
 * Gating and lifecycle logging of one feature. The facade is an `object` extending this. [widened]: the feature uses
 * members its `.classtweaker` makes public.
 */
abstract class Feature(val name: String, modName: String, private val widened: Boolean = false) {
  private enum class State { UNDECIDED, ACTIVE, INACTIVE, DISABLED }

  @PublishedApi
  internal val prefix = "[$modName] $name: "

  @Volatile
  private var state = State.UNDECIDED

  @Volatile
  private var forced = false

  @Volatile
  private var resolved = false

  /** The feature's mod option to accept untested target versions. */
  protected val force: Boolean
    get() = forced

  /**
   * Why the feature cannot run, as a sentence a player can act on: a target mod is missing or untested. Null when it can.
   */
  protected abstract fun problem(): String?

  /**
   * Prepares what the compiler cannot check (e.g. native functions); runs once, the first time [problem] is null, after
   * the hooked methods are checked. Throws when something does not match.
   */
  protected open fun resolve() {}

  /** What [resolve] was doing, for the error line when it throws. */
  protected open fun resolveContext(): String = "preparing to run"

  /** Shown as `ready (<detail>)`. */
  protected open fun readyDetail(): String? = null

  fun init() {
    val why = try {
      why()
    } catch (t: Throwable) {
      disable("checking whether it can run", t)
      return
    }
    if (why != null) {
      log { "idle: $why" }
      return
    }
    if (!resolveOnce()) {
      return
    }
    val detail = try {
      readyDetail()
    } catch (t: Throwable) {
      disable("checking whether it can run", t)
      return
    }
    log { if (detail == null) "ready" else "ready ($detail)" }
  }

  /** Every hook checks this first. */
  fun active(): Boolean {
    val s = state
    return if (s == State.UNDECIDED) decide() else s == State.ACTIVE
  }

  private fun decide(): Boolean {
    val why = try {
      why()
    } catch (t: Throwable) {
      disable("checking whether it can run", t)
      return false
    }
    if (why == null && !resolveOnce()) {
      return false
    }
    if (!move(State.UNDECIDED, if (why == null) State.ACTIVE else State.INACTIVE)) {
      return state == State.ACTIVE // another thread got there first
    }
    log { if (why == null) "active" else "inactive: $why" }
    return why == null
  }

  // the feature's own reason first: without its target mod, widening is irrelevant to the player
  private fun why(): String? {
    val problem = problem()
    if (problem != null || !widened) {
      return problem
    }
    val widening = wideningProblem() ?: return null
    return "access widening is not in effect: $widening"
  }

  @Synchronized
  private fun resolveOnce(): Boolean {
    if (resolved) {
      return true
    }
    try {
      checkHookTargets(name)
    } catch (t: Throwable) {
      disable("checking the methods it hooks", t)
      return false
    }
    try {
      resolve()
    } catch (t: Throwable) {
      disable(resolveContext(), t)
      return false
    }
    resolved = true
    return true
  }

  /** Open so the facade can annotate it with `@LuaGlobal`; overrides call `super`. */
  open fun setForce(value: Boolean) {
    if (forced != value) {
      forced = value
      if (!move(State.ACTIVE, State.UNDECIDED)) {
        move(State.INACTIVE, State.UNDECIDED)
      }
    }
  }

  /** Turns the feature off for the rest of the session. */
  fun disable(what: String, t: Throwable) {
    if (!moveToDisabled()) {
      return
    }
    DebugType.Mod.printException(t, "${prefix}disabled after an error while $what", LogSeverity.Error)
  }

  inline fun log(message: () -> String) = DebugType.Mod.lzInfo { "$prefix${message()}" }

  inline fun debug(message: () -> String) = DebugType.Mod.lzDebug { "$prefix${message()}" }

  @Synchronized
  private fun move(from: State, to: State): Boolean {
    if (state != from) {
      return false
    }
    state = to
    return true
  }

  @Synchronized
  private fun moveToDisabled(): Boolean {
    if (state == State.DISABLED) {
      return false
    }
    state = State.DISABLED
    return true
  }
}
