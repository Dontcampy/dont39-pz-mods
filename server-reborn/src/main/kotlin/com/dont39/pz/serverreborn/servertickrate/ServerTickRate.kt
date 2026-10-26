package com.dont39.pz.serverreborn.servertickrate

import com.dont39.pz.serverreborn.ServerReborn
import com.dont39.pz.serverreborn.ServerRebornGate
import com.dont39.pz.serverreborn.servertickrate.TickScale.MAX_RATE
import com.dont39.pz.serverreborn.servertickrate.TickScale.VANILLA_RATE
import java.util.Locale
import zombie.SandboxOptions
import zombie.WorldSoundManager
import zombie.config.IntegerConfigOption
import zombie.core.PerformanceSettings
import zombie.core.random.Rand
import zombie.core.utils.UpdateLimit
import zombie.network.GameServer

/**
 * Runs a dedicated server's world tick at the rate its owner sets instead of the game's fixed 10 per second, and scales
 * the game's per-tick constants that assume 10 back to real time. Inert on clients: only `GameServer.main`'s calls act.
 */
internal object ServerTickRate {
  const val OPTION = "Dont39ServerReborn_ServerTickRate.TicksPerSecond"

  private const val VANILLA_PERIOD = 100L
  private const val MEASURE_NANOS = 10_000_000_000L

  // this mod, Zabric and ZombieBuddy: frames between a hooked method and the handler
  private val OWN_PACKAGES = listOf("com.dont39.", "com.pzlauncher.", "me.zed_0xff.")

  @Volatile
  private var decided = false

  // GameServer.main's thread, once its setLockFPS took the option's rate
  @Volatile
  private var mainThread: Thread? = null

  // the 100 ms limiter GameServer.main built just before it sets the lock fps
  @Volatile
  private var built: UpdateLimit? = null

  // what the compensations read: the game's rate until the limiter runs at another
  @Volatile
  private var rate = VANILLA_RATE

  // main thread only
  private var limit: UpdateLimit? = null
  private var measureStart = 0L
  private var measuredTicks = -1

  /** At `PerformanceSettings.setLockFPS` HEAD; the option is loaded with the save before `GameServer.main` calls it. */
  fun lockFps(original: Int): Int {
    if (decided || !GameServer.server || original != VANILLA_RATE || !calledFromServerMain()) {
      return original
    }
    decided = true
    val wanted = option()
    if (wanted == VANILLA_RATE) {
      ServerReborn.log { "tick rate off: sandbox option $OPTION is $VANILLA_RATE" }
      return original
    }
    check(wanted in VANILLA_RATE..MAX_RATE) { "sandbox option $OPTION is $wanted, outside $VANILLA_RATE-$MAX_RATE" }
    val limit = built
    if (limit == null) {
      ServerRebornGate.disable(
        "setting the server's tick rate",
        IllegalStateException("GameServer.main set the lock fps without building a $VANILLA_PERIOD ms limiter first"),
      )
      return original
    }
    built = null
    val period = TickScale.periodMillis(wanted)
    limit.setUpdatePeriod(period)
    this.limit = limit
    mainThread = Thread.currentThread()
    rate = wanted
    measuredTicks = 0
    ServerReborn.log { "tick rate on: $wanted ticks per second (period $period ms)" }
    ServerReborn.debug {
      "Rand.AdjustForFramerate(30) returns ${Rand.AdjustForFramerate(30)} (the game's server: 10)"
    }
    return wanted
  }

  /** At `UpdateLimit(long)` RETURN: the tick loop's limiter is a local of `GameServer.main`, taken as main builds it. */
  fun limitBuilt(limit: UpdateLimit, delay: Long) {
    // clients build limiters too; the server check spares them the stack walk
    if (decided || !GameServer.server || delay != VANILLA_PERIOD || !calledFromServerMain()) {
      return
    }
    built = limit
  }

  /** At `ServerMap.preupdate` HEAD, the start of each tick; also while the mod is off, to undo the rate. */
  fun tick() {
    if (Thread.currentThread() !== mainThread) {
      return
    }
    if (rate == VANILLA_RATE) {
      return
    }
    if (!ServerRebornGate.active()) {
      restore()
      return
    }
    measure()
  }

  /** At `Rand.AdjustForFramerate` RETURN: its server branch assumes 10 ticks per second. */
  fun adjustForFramerate(original: Int, n: Int): Int {
    val r = rate
    return if (r == VANILLA_RATE) original else TickScale.adjustForFramerate(n, r)
  }

  /** At RETURN of the `WorldSound.init` that sets a new sound's life in ticks. */
  fun soundCreated(sound: WorldSoundManager.WorldSound) {
    val r = rate
    if (r != VANILLA_RATE) {
      sound.life = TickScale.ticks(sound.life, r)
    }
  }

  // a missing or retyped option is a mismatch: it throws into the gate
  private fun option(): Int {
    val option = SandboxOptions.instance.getOptionByName(OPTION)
      ?: throw IllegalStateException("the sandbox option $OPTION is not registered; was sandbox-options.txt loaded?")
    val integer = option.asConfigOption() as? IntegerConfigOption
      ?: throw IllegalStateException("the sandbox option $OPTION is not an integer option")
    return integer.value
  }

  private fun measure() {
    val counted = measuredTicks
    if (counted < 0) {
      return
    }
    val now = System.nanoTime()
    if (counted == 0) {
      measureStart = now
    }
    val elapsed = now - measureStart
    if (elapsed < MEASURE_NANOS) {
      measuredTicks = counted + 1
      return
    }
    measuredTicks = -1
    val measured = counted * 1e9 / elapsed
    val period = checkNotNull(limit) { "the tick rate is set without a limiter" }.delay
    val expected = 1000.0 / period
    val text = String.format(Locale.ROOT, "%.2f", measured)
    when {
      // the limiter cannot run faster than its period: something else drives the ticks
      measured > expected * 1.05 -> mismatch("the server ticked $text times per second, above its limiter's $rate")
      measured < expected * 0.95 -> ServerReborn.error {
        "the server ticked $text times per second in its first 10 s, below $rate: its ticks took longer than " +
          "$period ms; a lower \"Server ticks per second\" sandbox option fits this server's load"
      }
      else -> ServerReborn.log { "the server ticked $text times per second in its first 10 s" }
    }
  }

  private fun mismatch(what: String) {
    ServerRebornGate.disable("setting the server's tick rate", IllegalStateException(what))
    restore()
  }

  private fun restore() {
    rate = VANILLA_RATE
    measuredTicks = -1
    limit?.setUpdatePeriod(VANILLA_PERIOD)
    limit = null
    PerformanceSettings.setLockFPS(VANILLA_RATE)
    ServerReborn.log { "the server is back at $VANILLA_RATE ticks per second" }
  }

  // the first frame outside the mod, the loader and the hooked method
  private fun calledFromServerMain(): Boolean = StackWalker.getInstance().walk { frames ->
    frames
      .filter { f -> OWN_PACKAGES.none { f.className.startsWith(it) } }
      .skip(1)
      .findFirst()
      .map { it.className == "zombie.network.GameServer" && it.methodName == "main" }
      .orElse(false)
  }
}
