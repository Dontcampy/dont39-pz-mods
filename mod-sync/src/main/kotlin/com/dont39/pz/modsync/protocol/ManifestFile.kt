package com.dont39.pz.modsync.protocol

import kotlinx.serialization.Serializable

/** [path] relative to the item folder, under `mods/`. */
@Serializable
internal data class ManifestFile(val path: String, val size: Long, val sha256: String)
