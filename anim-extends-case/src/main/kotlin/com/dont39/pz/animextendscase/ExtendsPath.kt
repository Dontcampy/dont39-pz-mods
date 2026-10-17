package com.dont39.pz.animextendscase

import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.ModifyReturnValue
import com.pzlauncher.zabric.hook.Self
import java.nio.file.Files
import java.nio.file.Paths
import zombie.ZomboidFileSystem

/** Uses the original-case path when only that one names a file. */
@Hooks(ZomboidFileSystem::class)
internal object ExtendsPath {
  @ModifyReturnValue(method = ["resolveRelativePath"])
  fun keepCase(original: String?, @Self fs: ZomboidFileSystem, parent: String?, name: String?): String? {
    if (original == null) return null
    // mapped by activeFileMap, or the game's path works (e.g. case-insensitive file systems)
    if (fs.resolveFileOrGUID(original) != original || Files.isRegularFile(Paths.get(original))) return original
    // joined as the game does, before its lowercasing; the game has already rejected a null parent or name
    val joined = Paths.get(parent!!).parent.resolve(name!!)
    // neither exists: keep the game's path so its missing-file error stays as before
    return if (Files.isRegularFile(joined)) joined.toString() else original
  }
}
