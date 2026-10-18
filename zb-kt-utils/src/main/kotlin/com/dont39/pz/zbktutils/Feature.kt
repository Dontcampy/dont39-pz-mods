package com.dont39.pz.zbktutils

import com.dont39.pz.zbktutils.LoggerUtils.lzDebug
import com.dont39.pz.zbktutils.LoggerUtils.lzInfo
import com.pzlauncher.zabric.hook.HookGate
import com.pzlauncher.zabric.hook.ZabricHooks
import zombie.debug.DebugType
import zombie.debug.LogSeverity

/**
 * Gating and lifecycle logging of one feature. The facade is an `object` extending this, and the gate of the module's
 * hook classes. [modId]: the mod that ships the feature, also the name of its Zabric list. [widened]: the feature uses
 * members its `.classtweaker` makes public.
 */
abstract class Feature(val name: String, private val modId: String, private val widened: Boolean = false) : HookGate {
  private enum class State { UNDECIDED, ACTIVE, INACTIVE, DISABLED }

  @PublishedApi
  internal val prefix = "[$modId] $name: "

  @Volatile
  private var state = State.UNDECIDED

  @Volatile
  private var forced = false

  @Volatile
  private var resolved = false

  // written before the state it explains
  @Volatile
  private var reason: String? = null

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
    // Zabric's weave check may have disabled it already; the last status line must still read disabled
    if (state == State.DISABLED) {
      return
    }
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

  /** Null when the feature runs, else why not, as a sentence for the player. Decides like [active]. */
  fun whyNotActive(): String? = if (active()) null else checkNotNull(reason) { "${prefix}not active, no reason kept" }

  /** The generated glue checks this before every hook. */
  override fun active(): Boolean {
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
    if (!move(State.UNDECIDED, if (why == null) State.ACTIVE else State.INACTIVE, why)) {
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
    val widening = wideningProblem(modId, javaClass) ?: return null
    return "access widening is not in effect: $widening"
  }

  @Synchronized
  private fun resolveOnce(): Boolean {
    if (resolved) {
      return true
    }
    // init and the first hook may race here; a failure is final, not retried and reported again
    if (state == State.DISABLED) {
      return false
    }
    try {
      ZabricHooks.gateProblem(javaClass)?.let { throw IllegalStateException(it) }
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

  /**
   * Turns the feature off for the rest of the session. Later errors, e.g. while undoing, are still reported, worded so
   * the last status line still reads disabled.
   */
  fun disable(what: String, t: Throwable) {
    val first = moveToDisabled(what)
    val message = if (first) "disabled after an error while $what" else "disabled; then an error while $what"
    DebugType.Mod.printException(t, "$prefix$message", LogSeverity.Error)
  }

  /** What a hook throws, from the generated glue. */
  override fun failed(what: String, t: Throwable) = disable(what, t)

  inline fun log(message: () -> String) = DebugType.Mod.lzInfo { "$prefix${message()}" }

  inline fun debug(message: () -> String) = DebugType.Mod.lzDebug { "$prefix${message()}" }

  @Synchronized
  private fun move(from: State, to: State, why: String? = null): Boolean {
    if (state != from) {
      return false
    }
    why?.let { reason = it }
    state = to
    return true
  }

  @Synchronized
  private fun moveToDisabled(what: String): Boolean {
    if (state == State.DISABLED) {
      return false
    }
    reason = "it was disabled after an error while $what (details in console.txt)"
    state = State.DISABLED
    return true
  }
}
