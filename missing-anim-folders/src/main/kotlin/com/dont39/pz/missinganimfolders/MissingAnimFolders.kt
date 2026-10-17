package com.dont39.pz.missinganimfolders

import com.dont39.pz.zbktutils.Feature

/**
 * The game walks every mod's `media/AnimSets` and `media/actiongroups` without checking they exist, and logs a full
 * stack trace for each missing one; with many mods that floods the log and slows loading, mostly at server join.
 */
object MissingAnimFolders : Feature("missing-anim-folders", "Dont39AllInOne") {
  override fun problem(): String? = null
}
