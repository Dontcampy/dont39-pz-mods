package com.dont39.pz.servertickrate

import com.dont39.pz.zbktutils.Feature
import zombie.core.utils.UpdateLimit
import zombie.network.GameServer

/** PROTOTYPE: runs the dedicated server's world tick at `-Ddont39.serverTickHz` instead of the hardcoded 10 Hz. */
object ServerTickRate : Feature("server-tick-rate", "Dont39AllInOne") {
  private const val VANILLA_HZ = 10
  private const val VANILLA_PERIOD = 100L

  private val hz: Int = Integer.getInteger("dont39.serverTickHz", VANILLA_HZ)

  // the server's main thread once GameServer.main called setLockFPS(10); then its tick limiter's first Check
  @Volatile private var mainThread: Thread? = null
  @Volatile private var applied = false

  override fun problem(): String? = null

  fun lockFps(original: Int): Int {
    if (mainThread != null || !GameServer.server || original != VANILLA_HZ || !calledFromServerMain()) return original
    mainThread = Thread.currentThread()
    TickProbe.start(hz)
    log { "tick rate $hz Hz" + if (hz == VANILLA_HZ) " (vanilla)" else " (period ${1000L / hz} ms)" }
    return hz
  }

  fun limitChecked(limit: UpdateLimit) {
    if (applied || mainThread !== Thread.currentThread() || limit.delay != VANILLA_PERIOD || !calledFromServerMain()) return
    applied = true
    limit.setUpdatePeriod(1000L / hz)
  }

  private fun calledFromServerMain(): Boolean = StackWalker.getInstance().walk { frames ->
    frames.filter { !it.className.startsWith("com.dont39.") && !it.className.startsWith("com.pzlauncher.") && !it.className.startsWith("me.zed_0xff.") }
      .skip(1).findFirst().map { it.className == "zombie.network.GameServer" && it.methodName == "main" }.orElse(false)
  }
}
