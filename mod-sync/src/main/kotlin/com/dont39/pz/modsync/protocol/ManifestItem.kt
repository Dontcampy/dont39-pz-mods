package com.dont39.pz.modsync.protocol

import kotlinx.serialization.Serializable

/** [timestamp]: the server's install timestamp, the value connection-details sends for the item. */
@Serializable
internal data class ManifestItem(val id: String, val timestamp: Long, val files: List<ManifestFile>) {
  val bytes: Long
    get() = files.sumOf { it.size }
}
