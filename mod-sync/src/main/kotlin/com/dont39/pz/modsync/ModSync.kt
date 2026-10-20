package com.dont39.pz.modsync

import com.dont39.pz.zbktutils.Feature

/**
 * Lets a player whose Workshop copy of an item differs from the dedicated server's join with the server's copy. The
 * server serves its Workshop items over HTTP and tells joining clients where; a client that hits a version mismatch
 * downloads only the files that differ and plays that session on the server's copy.
 */
object ModSync : Feature("mod-sync", "Dont39AllInOne", widened = true) {
  override fun problem(): String? = null
}
