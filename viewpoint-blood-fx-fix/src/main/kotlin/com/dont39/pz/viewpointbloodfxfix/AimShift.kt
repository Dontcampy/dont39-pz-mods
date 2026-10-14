package com.dont39.pz.viewpointbloodfxfix

import bloodfx.Aim
import com.dont39.pz.zbktutils.glue.Hook
import zombie.iso.IsoMovingObject

/**
 * Aim.target tests the crosshair ray against flat-ground bones, but Viewpoint draws the zombie raised by the relief where
 * it stands; lowering the ray by that much for the call gives the same test, with the hit point on flat ground.
 */
internal object AimShift {
  private var original: Aim.Ray? = null
  private var lowered: Aim.Ray? = null

  @Hook.Enter(className = "bloodfx.Aim", methodName = "target")
  fun lower(@Hook.Argument(0) character: Any?) {
    val ray = Aim.last
    val lift = Relief.current()
    if (ray == null || lift == null || character !is IsoMovingObject) {
      return
    }
    val relief = Relief.at(lift, character.x.toDouble(), character.y.toDouble())
    if (LiftOffsets.debug()) {
      ViewpointBloodFxFix.log {
        val h = ray.oh()
        "aim: zombie at %.2f %.2f z=%.2f, relief %.3f, ray h %.3f -> %.3f".format(
          character.x, character.y, character.z, relief, h, h - relief,
        )
      }
    }
    if (relief == 0.0f) {
      return
    }
    val shifted = Aim.Ray(ray.ox(), ray.oy(), ray.oh() - relief, ray.dx(), ray.dy(), ray.dh(), ray.nanos())
    original = ray
    lowered = shifted
    Aim.last = shifted
  }

  @Hook.Exit(className = "bloodfx.Aim", methodName = "target")
  fun restore() {
    val shifted = lowered ?: return
    val saved = original
    original = null
    lowered = null
    // the renderer may have saved a newer ray meanwhile
    if (Aim.last === shifted) {
      Aim.last = saved
    }
  }
}
