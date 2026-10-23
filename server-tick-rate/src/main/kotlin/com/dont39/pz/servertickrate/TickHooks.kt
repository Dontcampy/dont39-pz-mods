package com.dont39.pz.servertickrate

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
import zombie.network.ServerMap

@Hooks(PerformanceSettings::class)
internal object LockFps {
  @ModifyVariable(method = ["setLockFPS"], at = At("HEAD"), argsOnly = true)
  fun lockFps(original: Int): Int = ServerTickRate.lockFps(original)
}

// the limiter's constructor cannot be hooked alone: ZombieBuddy's matcher also takes its (JJ)V overload
@Hooks(UpdateLimit::class)
internal object LimitCheck {
  @Inject(method = ["Check"], at = [At("HEAD")])
  fun check(@Self limit: UpdateLimit, ci: CallbackInfoReturnable<Boolean>) = ServerTickRate.limitChecked(limit)
}

@Hooks(ServerMap::class)
internal object TickStart {
  // always: puts the game's rate back once the feature is off, including after an error
  @Inject(method = ["preupdate"], at = [At("HEAD")], always = true)
  fun tick(ci: CallbackInfo) = ServerTickRate.tick()
}

@Hooks(Rand::class)
internal object FramerateRoll {
  @ModifyReturnValue(method = ["AdjustForFramerate"])
  fun adjusted(original: Int, n: Int): Int = ServerTickRate.adjustForFramerate(original, n)
}

// every other init but the copy ends in this one, which sets the life
@Hooks(WorldSoundManager.WorldSound::class)
internal object SoundLife {
  @Inject(method = ["init(Ljava/lang/Object;IIIIIFFS)Lzombie/WorldSoundManager\$WorldSound;"], at = [At("RETURN")])
  fun initialized(
    @Self sound: WorldSoundManager.WorldSound,
    ci: CallbackInfoReturnable<WorldSoundManager.WorldSound>,
  ) = ServerTickRate.soundCreated(sound)
}
