package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.ManifestItem
import java.util.Locale

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
