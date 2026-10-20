package com.dont39.pz.modsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

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

/**
 * How one item's tree is built: [copy] from the client's own copy, [download] from the server. [jarChanges]: jars the
 * server's copy has in another version, or that only one side has; their classes were loaded at game start.
 */
internal data class Plan(
  val item: ManifestItem,
  val copy: List<ManifestFile>,
  val download: List<ManifestFile>,
  val jarChanges: List<String>,
) {
  val downloadBytes: Long
    get() = download.sumOf { it.size }

  companion object {
    fun of(item: ManifestItem, local: LocalCopy): Plan {
      val (copy, download) = item.files.partition { local.hashes[it.path] == it.sha256 }
      val listed = item.files.mapTo(hashSetOf()) { it.path }
      val changed = item.files.filter { isJar(it.path) && local.hashes[it.path] != it.sha256 }.map { it.path }
      val removed = local.files.filter { isJar(it) && it !in listed }
      return Plan(item, copy, download, (changed + removed).sorted())
    }

    fun isJar(path: String): Boolean = path.lowercase(Locale.ROOT).endsWith(".jar")
  }
}
