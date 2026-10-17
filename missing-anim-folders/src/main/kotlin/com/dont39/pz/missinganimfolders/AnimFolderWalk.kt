package com.dont39.pz.missinganimfolders

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfoReturnable
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import zombie.core.skinnedmodel.advancedanimation.AdvancedAnimator

/** Skips the walk of a folder that is not there, so the game's walker does not log it as a failure. */
@Hooks(AdvancedAnimator::class)
internal object AnimFolderWalk {
  @Inject(method = ["searchFolders"], at = [At("HEAD")], cancellable = true)
  fun skipMissing(uri: URI?, folder: Path?, ci: CallbackInfoReturnable<List<String>>) {
    // a null or unreadable folder keeps the game's error
    if (folder != null && Files.notExists(folder)) {
      // mutable, like the list the game returns
      ci.returnValue = ArrayList()
    }
  }
}
