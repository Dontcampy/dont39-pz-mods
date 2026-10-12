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

  @JvmField
  val PROBLEM: String?

  private val DROPS: List<*>?
  private val MISTS: List<*>?
  private val PIECES: List<*>?
  private val SPLATS: Array<Any?>?
  private val SPLAT_COUNT: Field?
  private val DROP: Coords?
  private val MIST: Coords?
  private val SPLAT: Coords?
  private val PIECE: Coords?
  private val BLOOD = Saved()
  private val GORE = Saved()
  private val STATS = Stats()

  init {
    val r = Resolver()
    val blood = r.type("bloodfx.BloodFx")
    DROPS = r.staticValue(blood, "drops", List::class.java)
    MISTS = r.staticValue(blood, "mists", List::class.java)
    SPLATS = r.staticValue(blood, "splats", Array<Any?>::class.java)
    SPLAT_COUNT = r.field(blood, "splatCount")
    PIECES = r.staticValue(r.type("bloodfx.GoreGpu"), "pieces", List::class.java)
    DROP = coords(r, "bloodfx.BloodFx\$Drop", "x", "y", "h")
    MIST = coords(r, "bloodfx.BloodFx\$Mist", "x", "y", "h")
    SPLAT = coords(r, "bloodfx.BloodFx\$Splat", "ox", "oy", "oh")
    PIECE = coords(r, "bloodfx.Gore\$Piece", "x", "y", "h")
    PROBLEM = r.problem
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
        val drops = DROPS!!
        val drop = DROP!!
        for (i in 0 until drops.size) {
          BLOOD.lift(lift, drops[i], drop)
        }
        val mists = MISTS!!
        val mist = MIST!!
        for (i in 0 until mists.size) {
          BLOOD.lift(lift, mists[i], mist)
        }
        val splats = SPLATS!!
        val splat = SPLAT!!
        for (i in 0 until Math.min(SPLAT_COUNT!!.getInt(null), splats.size)) {
          val owner = splats[i]
          if (owner != null) {
            BLOOD.lift(lift, owner, splat)
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
        val pieces = PIECES!!
        val piece = PIECE!!
        for (i in 0 until pieces.size) {
          GORE.lift(lift, pieces[i], piece)
        }
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

  private fun coords(r: Resolver, owner: String, x: String, y: String, h: String): Coords? {
    val type = r.type(owner)
    val fx = r.field(type, x)
    val fy = r.field(type, y)
    val fh = r.field(type, h)
    return if (fx == null || fy == null || fh == null) null else Coords(fx, fy, fh)
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
