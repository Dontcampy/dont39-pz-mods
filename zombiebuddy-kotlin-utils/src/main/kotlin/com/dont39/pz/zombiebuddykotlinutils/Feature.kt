package com.dont39.pz.zombiebuddykotlinutils

import zombie.debug.DebugType
import zombie.debug.LogSeverity

/**
 * Gating and lifecycle logging of one feature (zombiebuddy-patching.md "Gating", logging-guidelines.md). The facade is
 * an `object` extending this; it supplies [problem] and, optionally, [readyDetail].
 *
 * Lines: `ready`/`idle` at [init], `active`/`inactive` on the first [active] call after [init] or after [setForce]
 * changed the value, `disabled after an error while ...` on [disable]. All carry the prefix `[<modName>] <name>: `.
 */
abstract class Feature(val name: String, modName: String = "Dont39AllInOne") {
  private enum class State { UNDECIDED, ACTIVE, INACTIVE, DISABLED }

  private val prefix = "[$modName] $name: "

  @Volatile
  private var state = State.UNDECIDED

  @Volatile
  private var forced = false

  /** Set from the feature's mod option ([setForce]); [problem] reads it to accept untested target versions. */
  protected val force: Boolean
    get() = forced

  /** Why the feature cannot run, as a sentence a player can act on, or null when it can. May be called again later. */
  protected abstract fun problem(): String?

  /** Shown as `ready (<detail>)`, e.g. the target mod's version; null for a plain `ready`. */
  protected open fun readyDetail(): String? = null

  /** Called once by the mod's `Main`: logs whether the feature can run. */
  fun init() {
    val line = try {
      val why = problem()
      if (why != null) {
        "idle: $why"
      } else {
        val detail = readyDetail()
        if (detail == null) "ready" else "ready ($detail)"
      }
    } catch (t: Throwable) {
      disable("checking whether it can run", t)
      return
    }
    log(line)
  }

  /** Every patch entry checks this first. After the first decision it is a field read. */
  fun active(): Boolean {
    val s = state
    return if (s == State.UNDECIDED) decide() else s == State.ACTIVE
  }

  private fun decide(): Boolean {
    val why = try {
      problem()
    } catch (t: Throwable) {
      disable("checking whether it can run", t)
      return false
    }
    if (!move(State.UNDECIDED, if (why == null) State.ACTIVE else State.INACTIVE)) {
      return state == State.ACTIVE // decided or disabled meanwhile on another thread
    }
    log(if (why == null) "active" else "inactive: $why")
    return why == null
  }

  /**
   * From the mod options; the decision is made again on the next [active] call. Open so the facade can redeclare it to
   * annotate it for the Lua glue; an override must call `super.setForce`.
   */
  open fun setForce(value: Boolean) {
    if (forced != value) {
      forced = value
      if (!move(State.ACTIVE, State.UNDECIDED)) {
        move(State.INACTIVE, State.UNDECIDED)
      }
    }
  }

  /** Turns the feature off for the rest of the session and logs [t] once. [what]: "raising blood onto the terrain". */
  fun disable(what: String, t: Throwable) {
    if (!moveToDisabled()) {
      return
    }
    DebugType.Mod.printException(t, prefix + "disabled after an error while " + what, LogSeverity.Error)
  }

  fun log(message: String) {
    DebugType.Mod.println(prefix + message)
  }

  fun warn(message: String) {
    val line: Any = prefix + message // warn(Object), as the Java features call it; warn(String, ...) is a format call
    DebugType.Mod.warn(line)
  }

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
