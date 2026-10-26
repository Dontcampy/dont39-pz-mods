package com.dont39.pz.serverreborn

import com.dont39.pz.serverreborn.network.server.ServerSync
import com.dont39.pz.serverreborn.servertickrate.ServerTickRate
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import zombie.network.ServerMap
import zombie.popman.PoolCaps

/**
 * The dedicated server's main loop, which both parts run on: its thread, its tick rate, and the start of each pass and
 * the start and end of each world tick, handed to the parts in order. GameServer.main calls each hooked method once
 * per pass or tick, and nothing else calls them.
 */
internal object ServerLoop {
  @Volatile
  private var thread: Thread? = null

  /** Whether the caller runs on GameServer.main's loop; false before its first tick. */
  val onMainThread get() = Thread.currentThread() === thread

  /** Whether the loop ticked yet; before that, its thread is unknown. */
  val ticking get() = thread != null

  /** The world ticks per second now: the game's 10 unless the tick rate runs at another. */
  val ticksPerSecond get() = ServerTickRate.ticksPerSecond

  /** Each pass, ticking or not, before the game runs its queued packets. */
  fun passStarts() = ServerSync.passStarts()

  /** Each tick's start, also while the mod is off: the parts undo their changes on this thread. */
  fun tickStarts() {
    thread = Thread.currentThread()
    ServerTickRate.tick()
    ServerSync.tickStarts()
  }

  /** Each tick's end, after all of its sends, before cells unload. */
  fun tickEnds() = ServerSync.tickEnds()
}

@Hooks(ServerMap::class, gate = ServerReborn::class)
internal object TickHooks {
  // always: puts the game's tick rate back and drops the network sync's state once the mod is off, also after an error
  @Inject(method = ["preupdate"], at = [At("HEAD")], always = true)
  fun tickStarts(ci: CallbackInfo) = ServerLoop.tickStarts()

  @Inject(method = ["postupdate"], at = [At("HEAD")])
  fun tickEnds(ci: CallbackInfo) = ServerLoop.tickEnds()
}

/** PoolCaps.updateServerCaps: GameServer.main calls it first in each pass, also in passes that only take packets. */
@Hooks(PoolCaps::class, gate = ServerReborn::class)
internal object PassHooks {
  @Inject(method = ["updateServerCaps"], at = [At("HEAD")])
  fun passStarts(ci: CallbackInfo) = ServerLoop.passStarts()
}
