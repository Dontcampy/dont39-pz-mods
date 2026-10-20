package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.protocol.ManifestItem
import com.dont39.pz.modsync.protocol.SafePath
import com.dont39.pz.modsync.protocol.regularFiles
import com.dont39.pz.modsync.protocol.relativePath
import com.dont39.pz.modsync.protocol.sha256
import java.nio.file.Files
import java.nio.file.Path

/**
 * The client's own copy of an item: every file under its `mods/` ([files]), and the SHA-256 of those that could match
 * the manifest ([hashes]); a file of another size cannot, so it is not read.
 */
internal class LocalCopy(val root: Path?, val files: Set<String>, val hashes: Map<String, String>) {
  companion object {
    val NONE = LocalCopy(null, emptySet(), emptyMap())

    fun scan(itemFolder: Path, item: ManifestItem, progress: Progress? = null): LocalCopy {
      val mods = itemFolder.resolve(SafePath.MODS)
      if (!Files.isDirectory(mods)) {
        return LocalCopy(itemFolder, emptySet(), emptyMap())
      }
      val found = regularFiles(mods).associateBy { relativePath(itemFolder, it) }
      val hashes = item.files.mapNotNull { file ->
        progress?.checkCancelled()
        found[file.path]?.takeIf { Files.size(it) == file.size }?.let { file.path to sha256(it) }
      }.toMap()
      return LocalCopy(itemFolder, found.keys, hashes)
    }
  }
}
