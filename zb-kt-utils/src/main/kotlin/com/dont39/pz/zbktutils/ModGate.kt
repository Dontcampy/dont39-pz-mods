package com.dont39.pz.zbktutils

import com.dont39.pz.zbktutils.LoggerUtils.lzDebug
import com.dont39.pz.zbktutils.LoggerUtils.lzError
import com.dont39.pz.zbktutils.LoggerUtils.lzInfo
import com.dont39.pz.zbktutils.LoggerUtils.lzWarn
import com.pzlauncher.zabric.hook.HookGate
import com.pzlauncher.zabric.hook.ZabricHooks
import zombie.debug.DebugType
import zombie.debug.LogSeverity

/**
 * The gate and log of a standalone Kotlin mod whose parts its main entrypoint decides together, and which undo their
 * changes to the game on the first failure; the counterpart of [Feature], which decides lazily per feature. The mod's
 * facade is an `object` extending this, named as the gate of every hook object. Shut until [open], and for the rest of
 * the session after the first failure. Zabric's default gate is no substitute: it stops `always` hooks too and calls
 * nothing back.
 */
abstract class ModGate(modId: String) : HookGate {
  @PublishedApi
  internal val prefix = "[$modId] "

  @Volatile
  private var on = false

  @Volatile
  private var failed = false
  private var onFailure: (() -> Unit)? = null

  override fun active() = on

  /** Why the hook targets differ from the game the mod was built against, or null. */
  fun targetProblem(): String? = ZabricHooks.gateProblem(javaClass)

  /** Opens the hooks; [onFailure] undoes what the mod changed in the game and runs once, on the first failure. */
  @Synchronized
  fun open(onFailure: () -> Unit) {
    if (failed) return
    this.onFailure = onFailure
    on = true
  }

  /** Disables the mod for the session from its own code, as a hook's error would. */
  fun disable(what: String, t: Throwable) = failed(what, t)

  /** What a hook throws, from the generated glue. */
  override fun failed(what: String, t: Throwable) {
    val undo: (() -> Unit)?
    synchronized(this) {
      val first = !failed
      failed = true
      on = false
      if (!first) {
        error("error while $what, after it was disabled", t)
        return
      }
      undo = onFailure
      onFailure = null
    }
    error("disabled for this session after an error while $what", t)
    try {
      undo?.invoke()
    } catch (u: Throwable) {
      error("error while undoing its changes after it was disabled", u)
    }
  }

  inline fun log(message: () -> String) = DebugType.Mod.lzInfo { "$prefix${message()}" }

  inline fun warn(message: () -> String) = DebugType.Mod.lzWarn { "$prefix${message()}" }

  inline fun debug(message: () -> String) = DebugType.Mod.lzDebug { "$prefix${message()}" }

  inline fun error(message: () -> String) = DebugType.Mod.lzError { "$prefix${message()}" }

  fun error(message: String, t: Throwable) = DebugType.Mod.printException(t, "$prefix$message", LogSeverity.Error)
}
