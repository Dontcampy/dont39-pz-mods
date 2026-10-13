package com.dont39.pz.viewpointbloodfxfix

import bloodfx.BloodFx
import bloodfx.Gore
import bloodfx.GoreGpu
import com.dont39.pz.zombiebuddykotlinutils.glue.Hook

/**
 * Raises heights by the relief only for the duration of BloodFX's draw calls, so its simulation stays on flat ground.
 * Particle lists are only changed on the render thread, the same one that draws.
 */
internal object LiftOffsets {
  private const val STATS_EVERY = 5_000_000_000L
  private const val SAVED_INITIAL = 256
  private const val DROP = 0
  private const val MIST = 1
  private const val SPLAT = 2
  private const val PIECE = 3

  private val BLOOD = Saved()
  private val GORE = Saved()
  private val STATS = Stats()

  /** BloodFX's own "Log blood particles to console.txt" option. */
  fun debug(): Boolean = BloodFx.debug

  @Hook.Enter(className = "bloodfx.Gpu", methodName = "render")
  fun liftBlood() {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    BLOOD.restore() // never stack on a lift whose exit did not run
    try {
      val lift = Relief.current()
      if (lift != null) {
        val drops = BloodFx.drops
        for (i in 0 until drops.size) {
          val d = drops[i]
          val dh = Relief.at(lift, d.x.toDouble(), d.y.toDouble())
          if (dh != 0.0f) {
            BLOOD.save(d, DROP, d.h, dh)
            d.h += dh
          }
        }
        val mists = BloodFx.mists
        for (i in 0 until mists.size) {
          val m = mists[i]
          val dh = Relief.at(lift, m.x.toDouble(), m.y.toDouble())
          if (dh != 0.0f) {
            BLOOD.save(m, MIST, m.h, dh)
            m.h += dh
          }
        }
        val splats = BloodFx.splats
        for (i in 0 until BloodFx.splatCount.coerceAtMost(splats.size)) {
          val s = splats[i]
          if (s != null) {
            val dh = Relief.at(lift, s.ox.toDouble(), s.oy.toDouble())
            if (dh != 0.0f) {
              BLOOD.save(s, SPLAT, s.oh, dh)
              s.oh += dh
            }
          }
        }
      }
      if (debug()) {
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
        val pieces = GoreGpu.pieces
        for (i in 0 until pieces.size) {
          val p = pieces[i]
          val dh = Relief.at(lift, p.x.toDouble(), p.y.toDouble())
          if (dh != 0.0f) {
            GORE.save(p, PIECE, p.h, dh)
            p.h += dh
          }
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

  /**
   * Keeps the original heights so they are written back exactly, not recomputed.
   */
  private class Saved {
    var lowest = 0.0f
    var highest = 0.0f
    var count = 0
      private set

    private var owners = arrayOfNulls<Any>(SAVED_INITIAL)
    private var kinds = ByteArray(SAVED_INITIAL)
    private var heights = FloatArray(SAVED_INITIAL)

    /** [h] is the height before adding [dh]. */
    fun save(owner: Any, kind: Int, h: Float, dh: Float) {
      if (count == owners.size) {
        val size = count * 2
        owners = owners.copyOf(size)
        kinds = kinds.copyOf(size)
        heights = heights.copyOf(size)
      }
      owners[count] = owner
      kinds[count] = kind.toByte()
      heights[count] = h
      count++
      lowest = if (count == 1) dh else lowest.coerceAtMost(dh)
      highest = if (count == 1) dh else highest.coerceAtLeast(dh)
    }

    /** Never throws: it runs in the exit hook, inside BloodFX's draw call. */
    fun restore() {
      var failure: Throwable? = null
      var i = count - 1
      while (i >= 0) {
        val owner = owners[i]
        val h = heights[i]
        try {
          when (kinds[i].toInt()) {
            DROP -> (owner as BloodFx.Drop).h = h
            MIST -> (owner as BloodFx.Mist).h = h
            SPLAT -> (owner as BloodFx.Splat).oh = h
            else -> (owner as Gore.Piece).h = h
          }
        } catch (t: Throwable) {
          // keep restoring the rest
          if (failure == null) {
            failure = t
          }
        }
        owners[i] = null
        i--
      }
      count = 0
      if (failure != null) {
        ViewpointBloodFxFix.disable("restoring the heights it raised", failure)
      }
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
        lowest = if (raised == 0) saved.lowest else lowest.coerceAtMost(saved.lowest)
        highest = if (raised == 0) saved.highest else highest.coerceAtLeast(saved.highest)
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
