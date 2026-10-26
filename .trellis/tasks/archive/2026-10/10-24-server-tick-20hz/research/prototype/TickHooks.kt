package com.dont39.pz.servertickrate

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.ModifyVariable
import com.pzlauncher.zabric.hook.Self
import zombie.core.PerformanceSettings
import zombie.core.utils.UpdateLimit
import zombie.network.ServerMap
import zombie.worldMap.WorldMapVisitedServer

@Hooks(UpdateLimit::class)
internal object LimitChecked {
  @Inject(method = ["Check"], at = [At("HEAD")])
  fun checked(@Self limit: UpdateLimit, ci: CallbackInfoReturnable<Boolean>) = ServerTickRate.limitChecked(limit)
}

@Hooks(PerformanceSettings::class)
internal object LockFps {
  @ModifyVariable(method = ["setLockFPS"], at = At("HEAD"), argsOnly = true)
  fun lockFps(original: Int): Int = ServerTickRate.lockFps(original)
}

// PROTOTYPE probe: the tick body runs from ServerMap.preupdate to WorldMapVisitedServer.update
@Hooks(ServerMap::class)
internal object TickStart {
  @Inject(method = ["preupdate"], at = [At("HEAD")])
  fun start(ci: CallbackInfo) = TickProbe.tickStart()
}

@Hooks(WorldMapVisitedServer::class)
internal object TickEnd {
  @Inject(method = ["update"], at = [At("RETURN")])
  fun end(ci: CallbackInfo) = TickProbe.tickEnd()
}
