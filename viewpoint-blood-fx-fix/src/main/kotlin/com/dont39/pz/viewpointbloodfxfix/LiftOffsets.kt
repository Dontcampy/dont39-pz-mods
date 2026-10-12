package com.dont39.pz.viewpointbloodfxfix

import com.dont39.pz.zombiebuddykotlinutils.Resolver
import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import java.lang.reflect.Field

/**
 * Raises heights by the relief only for the duration of BloodFX's draw calls, so its simulation stays on flat ground.
 * Particle lists are only changed on the render thread, the same one that draws.
 */
internal object LiftOffsets {
  private const val STATS_EVERY = 5_000_000_000L
  private const val SAVED_INITIAL = 256

  private lateinit var drops: List<*>
  private lateinit var mists: List<*>
  private lateinit var pieces: List<*>
  private lateinit var splats: Array<Any?>
  private lateinit var splatCount: Field
  private lateinit var drop: Coords
  private lateinit var mist: Coords
  private lateinit var splat: Coords
  private lateinit var piece: Coords
  private val BLOOD = Saved()
  private val GORE = Saved()
  private val STATS = Stats()

  fun resolve() {
    val blood = Resolver.type("bloodfx.BloodFx")
    drops = Resolver.staticValue(blood, "drops", List::class.java)
    mists = Resolver.staticValue(blood, "mists", List::class.java)
    splats = Resolver.staticValue(blood, "splats", Array<Any?>::class.java)
    splatCount = Resolver.field(blood, "splatCount")
    pieces = Resolver.staticValue(Resolver.type("bloodfx.GoreGpu"), "pieces", List::class.java)
    drop = coords("bloodfx.BloodFx\$Drop", "x", "y", "h")
    mist = coords("bloodfx.BloodFx\$Mist", "x", "y", "h")
    splat = coords("bloodfx.BloodFx\$Splat", "ox", "oy", "oh")
    piece = coords("bloodfx.Gore\$Piece", "x", "y", "h")
  }

  @Hook.Enter(className = "bloodfx.Gpu", methodName = "render")
  fun liftBlood() {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    BLOOD.restore() // never stack on a lift whose exit did not run
    try {
      val lift = Relief.current()
      if (lift != null) {
        liftAll(BLOOD, lift, drops, drop)
        liftAll(BLOOD, lift, mists, mist)
        val owners = splats
        val c = splat
        for (i in 0 until Math.min(splatCount.getInt(null), owners.size)) {
          val owner = owners[i]
          if (owner != null) {
            BLOOD.lift(lift, owner, c)
          }
        }
      }
      if (ViewpointBloodFxFix.debug()) {
        STATS.frame(lift != null, BLOOD)
      }
    } catch (t: Throwable) {
      BLOOD.restore()
      ViewpointBloodFxFix.disable("raising blood onto the terrain", t)
    }
  }

  @Hook.Exit(className = "bloodfx.Gpu", methodName = "render")
  fun restoreBlood() {
    BLOOD.restore()
  }

  @Hook.Enter(className = "bloodfx.GoreGpu", methodName = "render")
  fun liftGore() {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    GORE.restore()
    try {
      val lift = Relief.current()
      if (lift != null) {
        liftAll(GORE, lift, pieces, piece)
      }
    } catch (t: Throwable) {
      GORE.restore()
      ViewpointBloodFxFix.disable("raising severed parts onto the terrain", t)
    }
  }

  @Hook.Exit(className = "bloodfx.GoreGpu", methodName = "render")
  fun restoreGore() {
    GORE.restore()
  }

  private fun liftAll(saved: Saved, lift: Any, owners: List<*>, c: Coords) {
    for (i in 0 until owners.size) {
      saved.lift(lift, owners[i], c)
    }
  }

  private fun coords(owner: String, x: String, y: String, h: String): Coords {
    val type = Resolver.type(owner)
    return Coords(Resolver.field(type, x), Resolver.field(type, y), Resolver.field(type, h))
  }

  private class Coords(val x: Field, val y: Field, val h: Field)

  /**
   * Keeps the original heights so they are written back exactly, not recomputed.
   */
  private class Saved {
    var lowest = 0.0f
    var highest = 0.0f
    var count = 0
      private set
    private var owners = arrayOfNulls<Any>(SAVED_INITIAL)
    private var fields = arrayOfNulls<Field>(SAVED_INITIAL)
    private var heights = FloatArray(SAVED_INITIAL)

    fun lift(lift: Any, owner: Any?, c: Coords) {
      val dh = Relief.at(lift, c.x.getFloat(owner).toDouble(), c.y.getFloat(owner).toDouble())
      if (dh == 0.0f) {
        return
      }
      val h = c.h.getFloat(owner)
      if (count == owners.size) {
        val size = count * 2
        owners = owners.copyOf(size)
        fields = fields.copyOf(size)
        heights = heights.copyOf(size)
      }
      owners[count] = owner
      fields[count] = c.h
      heights[count] = h
      count++
      c.h.setFloat(owner, h + dh)
      lowest = if (count == 1) dh else Math.min(lowest, dh)
      highest = if (count == 1) dh else Math.max(highest, dh)
    }

    fun restore() {
      var i = count - 1
      while (i >= 0) {
        try {
          fields[i]!!.setFloat(owners[i], heights[i])
        } catch (e: Exception) {
          // keep restoring the rest
        }
        owners[i] = null
        i--
      }
      count = 0
    }
  }

  private class Stats {
    private var since = System.nanoTime()
    private var frames = 0
    private var withRelief = 0
    private var raised = 0
    private var lowest = 0.0f
    private var highest = 0.0f

    fun frame(relief: Boolean, saved: Saved) {
      frames++
      if (relief) {
        withRelief++
      }
      if (saved.count > 0) {
        lowest = if (raised == 0) saved.lowest else Math.min(lowest, saved.lowest)
        highest = if (raised == 0) saved.highest else Math.max(highest, saved.highest)
        raised += saved.count
      }
      val now = System.nanoTime()
      if (now - since >= STATS_EVERY) {
        ViewpointBloodFxFix.log(
          String.format(
            "%d frames, %d with terrain relief, %d particle draws raised by %.3f..%.3f",
            frames, withRelief, raised, lowest, highest,
          ),
        )
        since = now
        frames = 0
        withRelief = 0
        raised = 0
      }
    }
  }
}
