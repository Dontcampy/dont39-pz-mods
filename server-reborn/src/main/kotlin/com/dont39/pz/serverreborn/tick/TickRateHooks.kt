package com.dont39.pz.serverreborn.tick

import com.dont39.pz.serverreborn.ServerReborn
import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import com.pzlauncher.zabric.hook.ModifyReturnValue
import com.pzlauncher.zabric.hook.ModifyVariable
import com.pzlauncher.zabric.hook.Self
import zombie.WorldSoundManager
import zombie.core.PerformanceSettings
import zombie.core.random.Rand
import zombie.core.utils.UpdateLimit

@Hooks(PerformanceSettings::class, gate = ServerReborn::class)
internal object LockFps {
  @ModifyVariable(method = ["setLockFPS"], at = At("HEAD"), argsOnly = true)
  fun lockFps(original: Int): Int = ServerTickRate.lockFps(original)
}

// GameServer.main keeps its tick limiter in a local: it is taken where main builds it
@Hooks(UpdateLimit::class, gate = ServerReborn::class)
internal object LimitBuilt {
  @Inject(method = ["<init>(J)V"], at = [At("RETURN")])
  fun built(@Self limit: UpdateLimit, delay: Long, ci: CallbackInfo) = ServerTickRate.limitBuilt(limit, delay)
}

@Hooks(Rand::class, gate = ServerReborn::class)
internal object FramerateRoll {
  @ModifyReturnValue(method = ["AdjustForFramerate"])
  fun adjusted(original: Int, n: Int): Int = ServerTickRate.adjustForFramerate(original, n)
}

// every other init but the copy ends in this one, which sets the life
@Hooks(WorldSoundManager.WorldSound::class, gate = ServerReborn::class)
internal object SoundLife {
  @Inject(method = ["init(Ljava/lang/Object;IIIIIFFS)Lzombie/WorldSoundManager\$WorldSound;"], at = [At("RETURN")])
  fun initialized(
    @Self sound: WorldSoundManager.WorldSound,
    ci: CallbackInfoReturnable<WorldSoundManager.WorldSound>,
  ) = ServerTickRate.soundCreated(sound)
}
