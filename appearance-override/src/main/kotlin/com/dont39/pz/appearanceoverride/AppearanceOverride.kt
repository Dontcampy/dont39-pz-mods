package com.dont39.pz.appearanceoverride

import com.dont39.pz.zbktutils.Feature
import com.dont39.pz.zbktutils.glue.LuaGlobal

/**
 * Draws a worn item hidden or as another item type, on its wearer, on every other client and in the previews, while the
 * item itself stays as it is for gameplay, saves and the game's packets. Lua on the server, or in single player, marks
 * the item (`Dont39AllInOne_AppearanceOverrideShared.lua`); by itself the feature changes nothing visible.
 */
object AppearanceOverride : Feature("appearance-override", "Dont39AllInOne") {
  private const val DEBUG = "appearanceoverride.debug"

  /** Logs what each render consumer drew instead of the worn items, when that changes; for the in-game tests. */
  internal val debug by lazy { System.getProperty(DEBUG).toBoolean() }

  // the render hooks do nothing on a dedicated server: it draws nothing
  override fun problem(): String? = null

  override fun readyDetail(): String? = if (debug) {
    "debug: logs each change to what is drawn instead of a worn item (-D$DEBUG); leave it off for play"
  } else {
    null
  }

  /** For the Lua sync and API: null when the feature runs, else why not. */
  @LuaGlobal("Dont39AllInOne_getAppearanceOverrideProblem")
  fun luaProblem(): String? = whyNotActive()
}
