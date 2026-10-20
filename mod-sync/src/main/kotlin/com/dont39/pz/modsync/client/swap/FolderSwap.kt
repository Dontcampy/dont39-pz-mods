package com.dont39.pz.modsync.client.swap

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import zombie.ZomboidFileSystem

@Hooks(ZomboidFileSystem::class)
internal object FolderSwap {
  // always: the list must match what the session loaded until the swap turns off, even after an error
  @Inject(method = ["getInstalledItemModsFolders"], at = [At("RETURN")], always = true)
  fun listed(folders: MutableList<String>?, ci: CallbackInfo) {
    folders?.let(Swap::apply)
  }

  // always, as the list: a stale folder makes getModDetails scan the current list instead; loadMod, which takes a
  // null as "not installed", runs only after Reset()
  @Inject(method = ["getModDir"], at = [At("RETURN")], cancellable = true, always = true)
  fun modDir(ci: CallbackInfoReturnable<String?>) {
    if (Swap.idsStale && ci.returnValue != null) {
      ci.returnValue = null
    }
  }

  // always, as getModDir
  @Inject(method = ["Reset"], at = [At("RETURN")], always = true)
  fun reset(ci: CallbackInfo) {
    Swap.idsCleared()
  }
}
