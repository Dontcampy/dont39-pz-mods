package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zbktutils.Feature
import com.dont39.pz.zbktutils.glue.LuaGlobal
import com.dont39.pz.zbktutils.targetLoaded
import com.dont39.pz.zbktutils.targetVersion
import com.dont39.pz.zbktutils.versionProblem

/**
 * Viewpoint 0.1.6 raises the near world by terrain relief on the GPU; BloodFX still draws at flat-ground height, so its
 * blood sinks into rising ground and floats over falling ground.
 */
object ViewpointBloodFxFix : Feature("viewpoint-blood-fx-fix", "Dont39AllInOne", widened = true) {
  private const val BLOODFX_VERSION_CLASS = "bloodfx.Main"
  private const val OPTIONS_PAGE = "Dont39: Blood FX for Viewpoint fix"
  private const val FORCE_OPTION = "Apply to untested Blood FX versions"

  // a newer BloodFX may handle relief itself
  private val KNOWN_BLOODFX = setOf("1.5.1")

  override fun problem(): String? {
    val version = targetVersion(BLOODFX_VERSION_CLASS) ?: return "BloodFX (ViewpointBloodFX) is not loaded"
    if (!targetLoaded("viewpoint.Main")) {
      return "Viewpoint is not loaded"
    }
    // without terrain relief there is nothing to fix
    if (!targetLoaded("viewpoint.far.Relief")) {
      return "this Viewpoint has no terrain relief"
    }
    return versionProblem("BloodFX", version, KNOWN_BLOODFX, force, OPTIONS_PAGE, FORCE_OPTION)
  }

  override fun readyDetail(): String = "BloodFX ${targetVersion(BLOODFX_VERSION_CLASS)}"

  @LuaGlobal("Dont39AllInOne_setViewpointBloodFxFixForce")
  override fun setForce(value: Boolean) = super.setForce(value)
}
