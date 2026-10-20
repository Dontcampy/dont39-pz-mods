package com.dont39.pz.modsync.protocol

import kotlinx.serialization.json.Json

// a later format 1 server may add fields; a breaking change takes a new format number
internal val syncJson = Json { ignoreUnknownKeys = true }
