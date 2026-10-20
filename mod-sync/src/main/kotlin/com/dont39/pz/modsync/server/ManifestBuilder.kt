package com.dont39.pz.modsync.server

import com.dont39.pz.modsync.protocol.Manifest
import com.dont39.pz.modsync.protocol.ManifestFile
import com.dont39.pz.modsync.protocol.ManifestItem
import com.dont39.pz.modsync.protocol.SafePath
import com.dont39.pz.modsync.protocol.SyncException
import com.dont39.pz.modsync.protocol.regularFiles
import com.dont39.pz.modsync.protocol.relativePath
import com.dont39.pz.modsync.protocol.sha256
import java.nio.file.Files
import java.nio.file.Path

/** The server's manifest, from its Workshop items' install folders. */
internal object ManifestBuilder {
  /** One Workshop item the server runs: [folder] is its install folder. */
  data class Source(val id: Long, val timestamp: Long, val folder: Path)

  /** What the server serves: the manifest, its JSON, and each listed hash's file. */
  class Served(val manifest: Manifest, val json: ByteArray, val files: Map<String, Path>) {
    val fileCount: Int
      get() = manifest.items.sumOf { it.files.size }

    val bytes: Long
      get() = manifest.items.sumOf { it.bytes }
  }

  /** Hashes every file under each source's `mods/`; an item without that folder lists no files. */
  fun build(sources: List<Source>): Served {
    val files = linkedMapOf<String, Path>()
    val items = sources.distinctBy { it.id }.map { source ->
      check(Files.isDirectory(source.folder)) {
        "the install folder ${source.folder} of Workshop item ${source.id} does not exist"
      }
      val mods = source.folder.resolve(SafePath.MODS)
      val found = if (Files.isDirectory(mods)) regularFiles(mods) else emptyList()
      ManifestItem(
        source.id.toString(),
        source.timestamp,
        // by path text, so the same folder gives the same manifest on every platform
        found.map { relativePath(source.folder, it) to it }.sortedBy { it.first }.map { (relative, path) ->
          val hash = sha256(path)
          files.putIfAbsent(hash, path)
          ManifestFile(relative, Files.size(path), hash)
        },
      )
    }
    val manifest = Manifest(Manifest.FORMAT, items)
    // on the server this is a problem of its own files, not of a join
    try {
      manifest.validate()
    } catch (e: SyncException) {
      throw IllegalStateException("clients could not use the list of the server's mods: ${e.message}", e)
    }
    return Served(manifest, manifest.encode(), files)
  }
}
