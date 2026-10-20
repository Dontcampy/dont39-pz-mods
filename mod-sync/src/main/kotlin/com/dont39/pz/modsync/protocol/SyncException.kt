package com.dont39.pz.modsync.protocol

import java.io.IOException

/**
 * What the server sent or the cache holds is not usable: stops this join with [message] as the reason the player
 * sees. An [IOException], as network failures are: a runtime event, not an error of the feature.
 */
internal class SyncException(message: String, cause: Throwable? = null) : IOException(message, cause)
