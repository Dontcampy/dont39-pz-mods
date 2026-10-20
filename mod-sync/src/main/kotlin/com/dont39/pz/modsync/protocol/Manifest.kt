package com.dont39.pz.modsync.protocol

import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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

  /** Throws [SyncException] naming what a client could not use. */
  fun validate() {
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
  }
}
