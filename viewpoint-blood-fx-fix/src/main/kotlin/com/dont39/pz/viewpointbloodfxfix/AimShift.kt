package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Resolver
import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import me.zed_0xff.zombie_buddy.Patch
import zombie.iso.IsoMovingObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/**
 * Aim.target tests the crosshair ray against flat-ground bones, but Viewpoint draws the zombie raised by the relief where
 * it stands; lowering the ray by that much for the call gives the same test, with the hit point on flat ground.
 */
internal object AimShift {
  private val LAST: Field?
  private val OX: Field?
  private val OY: Field?
  private val OH: Field?
  private val DX: Field?
  private val DY: Field?
  private val DH: Field?
  private val NANOS: Field?
  private val RAY: Constructor<*>?
  private val PROBLEM: String?
  private var original: Any? = null
  private var lowered: Any? = null
  private var reported = false

  init {
    val r = Resolver()
    val aim = r.type("bloodfx.Aim")
    val ray = r.type("bloodfx.Aim\$Ray")
    LAST = r.field(aim, "last")
    OX = r.field(ray, "ox")
    OY = r.field(ray, "oy")
    OH = r.field(ray, "oh")
    DX = r.field(ray, "dx")
    DY = r.field(ray, "dy")
    DH = r.field(ray, "dh")
    NANOS = r.field(ray, "nanos")
    // Double::class.java / Long::class.java are the primitive classes
    val double = Double::class.java
    RAY = r.constructor(ray, double, double, double, double, double, double, Long::class.java)
    PROBLEM = r.problem
  }

  @Hook.Enter(className = "bloodfx.Aim", methodName = "target")
  fun lower(@Patch.Argument(0) character: Any?) {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    val problem = PROBLEM
    if (problem != null) {
      if (!reported) {
        reported = true
        ViewpointBloodFxFix.warn("aimed cuts not corrected for terrain relief: $problem")
      }
      return
    }
    try {
      val last = LAST!!
      val ray = last.get(null)
      val lift = Relief.current()
      if (ray == null || lift == null || character !is IsoMovingObject) {
        return
      }
      val dh = Relief.at(lift, character.getX().toDouble(), character.getY().toDouble())
      if (ViewpointBloodFxFix.debug()) {
        val h = OH!!.getDouble(ray)
        ViewpointBloodFxFix.log(
          String.format(
            "aim: zombie at %.2f %.2f z=%.2f, relief %.3f, ray h %.3f -> %.3f",
            character.getX(), character.getY(), character.getZ(), dh, h, h - dh,
          ),
        )
      }
      if (dh == 0.0f) {
        return
      }
      val shifted = RAY!!.newInstance(
        OX!!.getDouble(ray), OY!!.getDouble(ray), OH!!.getDouble(ray) - dh,
        DX!!.getDouble(ray), DY!!.getDouble(ray), DH!!.getDouble(ray), NANOS!!.getLong(ray),
      )
      original = ray
      lowered = shifted
      last.set(null, shifted)
    } catch (t: Throwable) {
      restore()
      ViewpointBloodFxFix.disable("lowering the aim ray onto the terrain", t)
    }
  }

  @Hook.Exit(className = "bloodfx.Aim", methodName = "target")
  fun restore() {
    val shifted = lowered ?: return
    try {
      // the renderer may have saved a newer ray meanwhile
      val last = LAST!!
      if (last.get(null) === shifted) {
        last.set(null, original)
      }
    } catch (e: Exception) {
    }
    original = null
    lowered = null
  }
}
