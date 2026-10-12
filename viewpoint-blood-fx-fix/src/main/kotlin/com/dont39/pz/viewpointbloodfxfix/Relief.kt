package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Resolver
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType

/**
 * Lift is measured from the ground under the camera, so it is about zero at the player's feet.
 */
internal object Relief {
  @JvmField
  val PROBLEM: String?
  private val CURRENT: MethodHandle? // () -> Object
  private val SCENE: MethodHandle? // (Object lift, double x, double y) -> float

  init {
    val r = Resolver()
    val lift = r.type("viewpoint.render.FarRelief\$Lift")
    CURRENT = r.static(
      r.type("viewpoint.far.Relief"), "current", r.methodType(lift), MethodType.methodType(Any::class.java),
    )
    val double = Double::class.java
    SCENE = r.virtual(
      lift, "scene", MethodType.methodType(Float::class.java, double, double),
      MethodType.methodType(Float::class.java, Any::class.java, double, double),
    )
    PROBLEM = r.problem
  }

  // invokeExact call sites must match the asType signatures exactly

  /**
   * Null when terrain relief is off.
   */
  fun current(): Any? = CURRENT!!.invokeExact()

  fun at(lift: Any, x: Double, y: Double): Float = SCENE!!.invokeExact(lift, x, y) as Float
}
