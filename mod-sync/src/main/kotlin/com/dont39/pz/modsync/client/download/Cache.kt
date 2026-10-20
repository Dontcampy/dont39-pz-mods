package com.dont39.pz.modsync.client.download

import com.dont39.pz.modsync.protocol.ManifestItem
import com.dont39.pz.modsync.protocol.SafePath
import com.dont39.pz.modsync.protocol.regularFiles
import com.dont39.pz.modsync.protocol.relativePath
import com.dont39.pz.modsync.protocol.syncJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Locale

/**
 * The client's mod sync folder: the game passes `Zomboid/Dont39AllInOne/ModSync`, tests their own. Each server item
 * version has its own tree, `items/<host>_<game port>/<item id>/<timestamp>/<item id>/`, holding `mods/` as the server
 * has it. The tree is named by the item id because the game takes a mod's Workshop id from that folder's name.
 */
internal class Cache(val root: Path) {
  val servers = ServerStore(root)

  fun tree(host: String, port: Int, item: ManifestItem): Path =
    root.resolve(ITEMS).resolve(serverFolder(host, port)).resolve(item.id).resolve(item.timestamp.toString())
      .resolve(item.id)

  /**
   * Whether [tree] holds exactly [item]'s files, as [build] verified them. Sizes only: every file was hashed when it
   * was written. Waits for a [build] in progress: a cancelled join's worker may still be ending one as the next join
   * starts.
   */
  @Synchronized
  fun ready(tree: Path, item: ManifestItem): Boolean {
    val marker = tree.resolve(MARKER)
    if (!Files.isRegularFile(marker)) {
      return false
    }
    val built = try {
      syncJson.decodeFromString(ManifestItem.serializer(), Files.readString(marker))
    } catch (_: IllegalArgumentException) {
      return false // our own file from an older or cut build: rebuilt like a missing one
    }
    if (built != item) {
      return false
    }
    val found = regularFiles(tree).filter { it != marker }.associate { relativePath(tree, it) to Files.size(it) }
    return found == item.files.associate { it.path to it.size }
  }

  /**
   * Builds [tree] for [plan] in a staging folder next to it, which replaces [tree] only once every file is in place
   * and verified, so a cut build is never used. A failed build leaves its staging folder for the next one to delete.
   */
  @Synchronized
  fun build(tree: Path, plan: Plan, local: LocalCopy, fetcher: Fetcher, progress: Progress) {
    val staging = tree.resolveSibling("${tree.fileName}$STAGING")
    deleteTree(staging)
    Files.createDirectories(staging)
    plan.copy.forEach { file ->
      progress.checkCancelled()
      val root = checkNotNull(local.root) { "a plan copies from a local copy without a folder" }
      Files.newInputStream(SafePath.resolve(root, file.path)).use { input ->
        writeVerified(input, SafePath.resolve(staging, file.path), file, "your copy", progress = null)
      }
    }
    plan.download.forEach { file ->
      fetcher.download(file, SafePath.resolve(staging, file.path), progress)
    }
    Files.writeString(staging.resolve(MARKER), syncJson.encodeToString(ManifestItem.serializer(), plan.item))
    progress.checkCancelled()
    deleteTree(tree)
    Files.move(staging, tree, ATOMIC_MOVE)
  }

  companion object {
    private const val ITEMS = "items"
    private const val MARKER = ".dont39-modsync.json"
    private const val STAGING = ".staging"

    private val UNSAFE = Regex("[^a-z0-9.-]")

    /** A folder name for any host, IPv6 literals included. */
    fun serverFolder(host: String, port: Int): String =
      "${UNSAFE.replace(host.trim().lowercase(Locale.ROOT), "_")}_$port"
  }
}
