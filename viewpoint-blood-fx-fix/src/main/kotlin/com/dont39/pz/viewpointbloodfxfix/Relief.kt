package com.dont39.pz.viewpointbloodfxfix

import viewpoint.render.FarRelief

/**
 * Lift is measured from the ground under the camera, so it is about zero at the player's feet.
 */
internal object Relief {
  /**
   * Null when terrain relief is off.
   */
  fun current(): FarRelief.Lift? = viewpoint.far.Relief.current()

  fun at(lift: FarRelief.Lift, x: Double, y: Double): Float = lift.scene(x, y)
}
