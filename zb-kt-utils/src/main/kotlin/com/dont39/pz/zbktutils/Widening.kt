package com.dont39.pz.zbktutils

import com.pzlauncher.zabric.widener.AccessWidener

/** Why the members in [modId]'s `.classtweaker` list, shipped in [host]'s jar, are not public, or null when they are. */
fun wideningProblem(modId: String, host: Class<*>): String? = AccessWidener.problem(modId, host)

/** Logs the widening outcome once at startup, checking it first; Zabric ran before the game's log was up. */
fun reportWidening(modId: String, host: Class<*>) {
  AccessWidener.report(modId, host)
}
