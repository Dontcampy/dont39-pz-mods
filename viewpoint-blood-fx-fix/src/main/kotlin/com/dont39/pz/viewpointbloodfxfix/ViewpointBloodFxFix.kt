package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Feature
import com.dont39.pz.zombiebuddykotlinutils.Resolver
import com.dont39.pz.zombiebuddykotlinutils.glue.LuaGlobal
import com.dont39.pz.zombiebuddykotlinutils.targetVersion
import com.dont39.pz.zombiebuddykotlinutils.versionProblem
import java.lang.reflect.Field

/**
 * Viewpoint 0.1.6 raises the near world by terrain relief on the GPU; BloodFX still draws at flat-ground height, so its
 * blood sinks into rising ground and floats over falling ground.
 */
object ViewpointBloodFxFix : Feature("viewpoint-blood-fx-fix") {
  private const val BLOODFX_VERSION_CLASS = "bloodfx.Main"

  // a newer BloodFX may handle relief itself
  private val KNOWN_BLOODFX = setOf("1.5.1")

  // BloodFX's own "Log blood particles to console.txt" option; null when it cannot be read, which only turns debug off
  private val DEBUG: Field? = Resolver().run { field(type("bloodfx.BloodFx"), "debug") }

  override fun problem(): String? {
    val version = targetVersion(BLOODFX_VERSION_CLASS) ?: return "BloodFX (ViewpointBloodFX) is not loaded"
    val relief = Relief.PROBLEM
    if (relief != null) {
      return "this Viewpoint has no terrain relief API ($relief)"
    }
    val untested = versionProblem(
      "BloodFX", version, KNOWN_BLOODFX, force, "Dont39: Blood FX for Viewpoint fix", "Apply to untested Blood FX versions",
    )
    if (untested != null) {
      return untested
    }
    val internals = LiftOffsets.PROBLEM
    return if (internals == null) null else "unexpected BloodFX internals ($internals)"
  }

  override fun readyDetail(): String = "BloodFX " + targetVersion(BLOODFX_VERSION_CLASS)

  /** From the mod options; the decision is made again on the next patched call. */
  @LuaGlobal("Dont39AllInOne_setViewpointBloodFxFixForce")
  override fun setForce(value: Boolean) = super.setForce(value)

  /** Whether BloodFX's debug option is on. Called per frame. */
  internal fun debug(): Boolean {
    val field = DEBUG ?: return false
    return try {
      field.getBoolean(null)
    } catch (e: Exception) {
      false
    }
  }
}
