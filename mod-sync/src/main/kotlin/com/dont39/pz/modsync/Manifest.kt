package com.dont39.pz.modsync

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

// a later format 1 server may add fields; a breaking change takes a new format number
internal val syncJson = Json { ignoreUnknownKeys = true }

/** [path] relative to the item folder, under `mods/`. */
@Serializable
internal data class ManifestFile(val path: String, val size: Long, val sha256: String)

/** [timestamp]: the server's install timestamp, the value connection-details sends for the item. */
@Serializable
internal data class ManifestItem(val id: String, val timestamp: Long, val files: List<ManifestFile>) {
  val bytes: Long
    get() = files.sumOf { it.size }
}

/** The server's `manifest.json`: every file under each of its Workshop items' `mods/`. */
@Serializable
internal data class Manifest(val format: Int, val items: List<ManifestItem>) {
  fun encode(): ByteArray = syncJson.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

  /** [id] at the version the server sent with the join; throws [SyncException] naming why it is not listed so. */
  fun item(id: Long, timestamp: Long): ManifestItem {
    val item = items.firstOrNull { it.id == id.toString() }
      ?: throw SyncException("the server does not offer Workshop item $id for download")
    if (item.timestamp != timestamp) {
      throw SyncException(
        "the server offers Workshop item $id at version ${item.timestamp}, not at $timestamp as it said at the join",
      )
    }
    return item
  }

  /** One Workshop item the server runs: [folder] is its install folder. */
  data class Source(val id: Long, val timestamp: Long, val folder: Path)

  /** What the server serves: the manifest, its JSON, and each listed hash's file. */
  class Served(val manifest: Manifest, val json: ByteArray, val files: Map<String, Path>) {
    val fileCount: Int
      get() = manifest.items.sumOf { it.files.size }

    val bytes: Long
      get() = manifest.items.sumOf { it.bytes }
  }

  companion object {
    const val FORMAT = 1

    // a hostile or broken server must not exhaust the client's memory
    const val MAX_BYTES = 64 * 1024 * 1024

    private val HEX = Regex("[0-9a-f]{64}")

    /** Throws [SyncException] naming what is wrong. */
    fun parse(bytes: ByteArray): Manifest {
      if (bytes.size > MAX_BYTES) {
        throw SyncException("the server's mod list is larger than ${MAX_BYTES / 1024 / 1024} MB")
      }
      val manifest = try {
        val element = syncJson.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        val format = ((element as? JsonObject)?.get("format") as? JsonPrimitive)?.intOrNull
        if (format != FORMAT) {
          throw SyncException(
            "the server's mod list has format ${format ?: "(none)"}, but this version of the mod reads only " +
              "$FORMAT; update Dont39 All-in-One",
          )
        }
        syncJson.decodeFromJsonElement(serializer(), element)
      } catch (e: SerializationException) {
        throw SyncException("the server's mod list is not valid: ${e.message}", e)
      }
      manifest.validate()
      return manifest
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
      val manifest = Manifest(FORMAT, items)
      // on the server this is a problem of its own files, not of a join
      try {
        manifest.validate()
      } catch (e: SyncException) {
        throw IllegalStateException("clients could not use the list of the server's mods: ${e.message}", e)
      }
      return Served(manifest, manifest.encode(), files)
    }
  }

  private fun validate() {
    val ids = hashSetOf<String>()
    items.forEach { item ->
      if (item.id.toLongOrNull()?.toString() != item.id) {
        throw SyncException("the server's mod list has the item id \"${item.id}\", which is not a Workshop id")
      }
      if (!ids.add(item.id)) {
        throw SyncException("the server's mod list has Workshop item ${item.id} twice")
      }
      // the game looks up mod files by lowercased path
      val paths = hashSetOf<String>()
      item.files.forEach { file ->
        SafePath.check(file.path)
        if (!paths.add(file.path.lowercase(Locale.ROOT))) {
          throw SyncException("the server's mod list has ${file.path} of Workshop item ${item.id} twice")
        }
        if (file.size < 0 || !HEX.matches(file.sha256)) {
          throw SyncException("the server's mod list has no valid size and SHA-256 for ${file.path}")
        }
      }
    }
  }
}
