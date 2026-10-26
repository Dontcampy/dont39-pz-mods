package com.dont39.pz.serverreborn

import com.pzlauncher.zabric.hook.HookGate
import com.pzlauncher.zabric.hook.ZabricHooks

/**
 * The gate of all the mod's hooks: shut until the main entrypoint decided, and for the rest of the session after the
 * first error. Its own gate, not Zabric's default one: that one stops the mod in Zabric, `always` hooks included, with
 * no callback, so the mod's changes to the game would stay without their undo.
 */
object ServerRebornGate : HookGate {
  @Volatile
  private var on = false

  @Volatile
  private var failed = false
  private var onFailure: (() -> Unit)? = null

  override fun active() = on

  /** Why the hook targets differ from the game the mod was built against, or null. */
  internal fun targetProblem(): String? = ZabricHooks.gateProblem(ServerRebornGate::class.java)

  /** [onFailure] undoes what the mod changed in the game; it runs once, on the first failure. */
  @Synchronized
  internal fun open(onFailure: () -> Unit) {
    if (failed) return
    this.onFailure = onFailure
    on = true
  }

  /** Disables the mod for the session from its own code, as a hook's error would. */
  internal fun disable(what: String, t: Throwable) = failed(what, t)

  override fun failed(what: String, t: Throwable) {
    val undo: (() -> Unit)?
    synchronized(this) {
      val first = !failed
      failed = true
      on = false
      if (!first) {
        ServerReborn.error("error while $what, after it was disabled", t)
        return
      }
      undo = onFailure
      onFailure = null
    }
    ServerReborn.error("disabled for this session after an error while $what", t)
    try {
      undo?.invoke()
    } catch (u: Throwable) {
      ServerReborn.error("error while undoing its changes after it was disabled", u)
    }
  }
}
