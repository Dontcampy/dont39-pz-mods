package com.dont39.pz.zbktutils

import com.dont39.pz.zabric.widener.AccessWidener
import com.dont39.pz.zbktutils.LoggerUtils.lzWarn
import zombie.debug.DebugType

// the preload mod's jar is missing when ZombieBuddy did not load it
private const val WIDENER = "com.dont39.pz.zabric.widener.AccessWidener"
private const val NOT_LOADED = "the Zabric mod's Java code is not loaded (enable that mod)"

/** Why the members in the features' `.classtweaker` lists are not public, or null when they are. */
fun wideningProblem(): String? = if (targetLoaded(WIDENER)) AccessWidener.problem() else NOT_LOADED

/** Logs the widening outcome once at startup, checking it first; the preload mod ran before the game's log was up. */
fun reportWidening(modName: String) {
  if (targetLoaded(WIDENER)) {
    AccessWidener.report(modName)
  } else {
    DebugType.Mod.lzWarn { "[$modName] access widener: not applied: $NOT_LOADED" }
  }
}
