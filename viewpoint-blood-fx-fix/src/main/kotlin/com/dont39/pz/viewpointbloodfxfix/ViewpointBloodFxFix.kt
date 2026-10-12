package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Feature
import com.dont39.pz.zombiebuddykotlinutils.Resolver
import com.dont39.pz.zombiebuddykotlinutils.glue.LuaGlobal
import com.dont39.pz.zombiebuddykotlinutils.targetLoaded
import com.dont39.pz.zombiebuddykotlinutils.targetVersion
import com.dont39.pz.zombiebuddykotlinutils.versionProblem
import java.lang.reflect.Field

/**
 * Viewpoint 0.1.6 raises the near world by terrain relief on the GPU; BloodFX still draws at flat-ground height, so its
 * blood sinks into rising ground and floats over falling ground.
 */
object ViewpointBloodFxFix : Feature("viewpoint-blood-fx-fix", "Dont39AllInOne") {
  private const val BLOODFX_VERSION_CLASS = "bloodfx.Main"
  private const val OPTIONS_PAGE = "Dont39: Blood FX for Viewpoint fix"
  private const val FORCE_OPTION = "Apply to untested Blood FX versions"

  // a newer BloodFX may handle relief itself
  private val KNOWN_BLOODFX = setOf("1.5.1")

  // BloodFX's own "Log blood particles to console.txt" option
  private lateinit var debugField: Field

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

  override fun resolve() {
    Relief.resolve()
    LiftOffsets.resolve()
    AimShift.resolve()
    debugField = Resolver.field(Resolver.type("bloodfx.BloodFx"), "debug")
  }

  override fun resolveContext(): String {
    val version = targetVersion(BLOODFX_VERSION_CLASS)
    val forced = if (version in KNOWN_BLOODFX) "" else " (untested, enabled by \"$FORCE_OPTION\")"
    return "looking up Viewpoint's terrain relief and BloodFX $version internals$forced"
  }

  override fun readyDetail(): String = "BloodFX " + targetVersion(BLOODFX_VERSION_CLASS)

  @LuaGlobal("Dont39AllInOne_setViewpointBloodFxFixForce")
  override fun setForce(value: Boolean) = super.setForce(value)

  internal fun debug(): Boolean = debugField.getBoolean(null)
}
