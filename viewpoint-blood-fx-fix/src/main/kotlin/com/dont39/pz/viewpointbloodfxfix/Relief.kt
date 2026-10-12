package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Resolver
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType

/**
 * Lift is measured from the ground under the camera, so it is about zero at the player's feet.
 */
internal object Relief {
  private lateinit var currentHandle: MethodHandle // () -> Object
  private lateinit var sceneHandle: MethodHandle // (Object lift, double x, double y) -> float

  fun resolve() {
    val lift = Resolver.type("viewpoint.render.FarRelief\$Lift")
    currentHandle = Resolver.static(
      Resolver.type("viewpoint.far.Relief"), "current", MethodType.methodType(lift),
      MethodType.methodType(Any::class.java),
    )
    val double = Double::class.java
    sceneHandle = Resolver.virtual(
      lift, "scene", MethodType.methodType(Float::class.java, double, double),
      MethodType.methodType(Float::class.java, Any::class.java, double, double),
    )
  }

  // invokeExact call sites must match the asType signatures exactly

  /**
   * Null when terrain relief is off.
   */
  fun current(): Any? = currentHandle.invokeExact()

  fun at(lift: Any, x: Double, y: Double): Float = sceneHandle.invokeExact(lift, x, y) as Float
}
