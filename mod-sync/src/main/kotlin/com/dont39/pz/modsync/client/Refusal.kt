package com.dont39.pz.modsync.client

import com.dont39.pz.modsync.protocol.ManifestItem
import com.dont39.pz.modsync.protocol.SafePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Items never taken from a server: those carrying the mods that load Java code and this feature itself, whose files
 * the running game already uses for itself.
 */
internal object Refusal {
  // the Workshop ids in each mod's workshop.txt; ZombieBuddy's from its Workshop page
  private val ITEMS = mapOf(
    3619862853L to "ZombieBuddy",
    3820726385L to "Zabric",
    3820734224L to "ZabricKotlin",
    3818388200L to "Dont39 All-in-One",
  )

  // by mod folder too, for a re-upload or a pack; the folders carry the mod ids
  private val MODS = mapOf(
    "zombiebuddy" to "ZombieBuddy",
    "zabric" to "Zabric",
    "zabrickotlin" to "ZabricKotlin",
    "dont39allinone" to "Dont39 All-in-One",
  )

  /** What of those [id] carries, by its Workshop id or one of its mod [folders]; null when none. */
  fun of(id: Long, folders: Collection<String>): String? =
    ITEMS[id] ?: folders.firstNotNullOfOrNull { MODS[it.lowercase(Locale.ROOT)] }

  /** The mod folders the server's copy of [item] has. */
  fun folders(item: ManifestItem): Set<String> = item.files.mapTo(sortedSetOf()) { it.path.split('/')[1] }

  /** The mod folders of a local item folder. */
  fun folders(itemFolder: Path): Set<String> {
    val mods = itemFolder.resolve(SafePath.MODS)
    if (!Files.isDirectory(mods)) {
      return emptySet()
    }
    return Files.list(mods).use { children ->
      children.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.toList().toSortedSet()
    }
  }
}
