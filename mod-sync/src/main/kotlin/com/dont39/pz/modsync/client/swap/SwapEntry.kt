package com.dont39.pz.modsync.client.swap

import com.dont39.pz.modsync.protocol.SafePath
import java.nio.file.Files
import java.nio.file.Path

/** One item of the folder swap: its own `<install>/mods`, and the tree's, which is null when the tree has no mods. */
internal class SwapEntry(val id: Long, val installMods: Path, val treeMods: Path?) {
  companion object {
    fun normalize(folder: String): Path = Path.of(folder).toAbsolutePath().normalize()

    fun of(id: Long, install: Path, tree: Path): SwapEntry = SwapEntry(
      id,
      normalize(install.resolve(SafePath.MODS).toString()),
      tree.resolve(SafePath.MODS).toAbsolutePath().normalize().takeIf { Files.isDirectory(it) },
    )
  }
}
