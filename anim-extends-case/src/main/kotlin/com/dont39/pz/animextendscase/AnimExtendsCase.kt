package com.dont39.pz.animextendscase

import com.dont39.pz.zbktutils.Feature

/**
 * The game lowercases the path of the file an XML extends (`x_extends`) or includes. Where mod files sit under the
 * game folder on a case-sensitive file system, as on a Linux steamcmd server, that path names no file and the
 * animation node fails to load.
 */
object AnimExtendsCase : Feature("anim-extends-case", "Dont39AllInOne") {
  override fun problem(): String? = null
}
