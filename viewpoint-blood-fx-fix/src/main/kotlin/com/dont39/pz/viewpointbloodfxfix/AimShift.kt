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
  private lateinit var last: Field
  private lateinit var ox: Field
  private lateinit var oy: Field
  private lateinit var oh: Field
  private lateinit var dx: Field
  private lateinit var dy: Field
  private lateinit var dh: Field
  private lateinit var nanos: Field
  private lateinit var newRay: Constructor<*>
  private var original: Any? = null
  private var lowered: Any? = null

  fun resolve() {
    val aim = Resolver.type("bloodfx.Aim")
    val ray = Resolver.type("bloodfx.Aim\$Ray")
    last = Resolver.field(aim, "last")
    ox = Resolver.field(ray, "ox")
    oy = Resolver.field(ray, "oy")
    oh = Resolver.field(ray, "oh")
    dx = Resolver.field(ray, "dx")
    dy = Resolver.field(ray, "dy")
    dh = Resolver.field(ray, "dh")
    nanos = Resolver.field(ray, "nanos")
    val double = Double::class.java
    newRay = Resolver.constructor(ray, double, double, double, double, double, double, Long::class.java)
  }

  @Hook.Enter(className = "bloodfx.Aim", methodName = "target")
  fun lower(@Patch.Argument(0) character: Any?) {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    try {
      val ray = last.get(null)
      val lift = Relief.current()
      if (ray == null || lift == null || character !is IsoMovingObject) {
        return
      }
      val relief = Relief.at(lift, character.getX().toDouble(), character.getY().toDouble())
      if (ViewpointBloodFxFix.debug()) {
        val h = oh.getDouble(ray)
        ViewpointBloodFxFix.log(
          String.format(
            "aim: zombie at %.2f %.2f z=%.2f, relief %.3f, ray h %.3f -> %.3f",
            character.getX(), character.getY(), character.getZ(), relief, h, h - relief,
          ),
        )
      }
      if (relief == 0.0f) {
        return
      }
      val shifted = newRay.newInstance(
        ox.getDouble(ray), oy.getDouble(ray), oh.getDouble(ray) - relief,
        dx.getDouble(ray), dy.getDouble(ray), dh.getDouble(ray), nanos.getLong(ray),
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
      if (last.get(null) === shifted) {
        last.set(null, original)
      }
    } catch (e: Exception) {
    }
    original = null
    lowered = null
  }
}
