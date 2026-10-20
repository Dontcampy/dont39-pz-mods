package com.dont39.pz.modsync

import java.nio.file.Files
import java.nio.file.Path

/** Writes a Workshop item folder: [files] by path relative to the item folder. */
internal fun writeItem(folder: Path, files: Map<String, String>): Path {
  files.forEach { (path, text) ->
    val file = folder.resolve(path)
    Files.createDirectories(file.parent)
    Files.writeString(file, text)
  }
  return folder
}

internal val SERVER_FILES = mapOf(
  "mods/Alpha/common/.keep" to "",
  "mods/Alpha/42/mod.info" to "name=Alpha\nid=Alpha\n",
  "mods/Alpha/42/media/lua/client/Alpha.lua" to "print('alpha 2')\n",
  "mods/Alpha/42/media/lua/shared/Shared.lua" to "return {}\n",
  "mods/Alpha/42/media/java/alpha.jar" to "jar v1",
  // outside mods/: not offered
  "preview.png" to "png",
)
