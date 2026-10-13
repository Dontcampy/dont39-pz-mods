package com.dont39.pz.viewpointbloodfxfix

import bloodfx.BloodFx
import bloodfx.Gore
import bloodfx.GoreGpu
import com.dont39.pz.zbktutils.glue.Hook

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

  private val blood = Saved()
  private val gore = Saved()
  private val stats = Stats()

  /** BloodFX's own "Log blood particles to console.txt" option. */
  fun debug(): Boolean = BloodFx.debug

  @Hook.Enter(className = "bloodfx.Gpu", methodName = "render")
  fun liftBlood() {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    blood.restore() // never stack on a lift whose exit did not run
    try {
      val lift = Relief.current()
      if (lift != null) {
        BloodFx.drops.forEach { d ->
          val dh = Relief.at(lift, d.x.toDouble(), d.y.toDouble())
          if (dh != 0.0f) {
            blood.save(d, DROP, d.h, dh)
            d.h += dh
          }
        }
        BloodFx.mists.forEach { m ->
          val dh = Relief.at(lift, m.x.toDouble(), m.y.toDouble())
          if (dh != 0.0f) {
            blood.save(m, MIST, m.h, dh)
            m.h += dh
          }
        }
        val splats = BloodFx.splats
        for (i in 0 until BloodFx.splatCount.coerceAtMost(splats.size)) {
          val s = splats[i]
          if (s != null) {
            val dh = Relief.at(lift, s.ox.toDouble(), s.oy.toDouble())
            if (dh != 0.0f) {
              blood.save(s, SPLAT, s.oh, dh)
              s.oh += dh
            }
          }
        }
      }
      if (debug()) {
        stats.frame(lift != null, blood)
      }
    } catch (t: Throwable) {
      blood.restore()
      ViewpointBloodFxFix.disable("raising blood onto the terrain", t)
    }
  }

  @Hook.Exit(className = "bloodfx.Gpu", methodName = "render")
  fun restoreBlood() {
    blood.restore()
  }

  @Hook.Enter(className = "bloodfx.GoreGpu", methodName = "render")
  fun liftGore() {
    if (!ViewpointBloodFxFix.active()) {
      return
    }
    gore.restore()
    try {
      val lift = Relief.current()
      if (lift != null) {
        GoreGpu.pieces.forEach { p ->
          val dh = Relief.at(lift, p.x.toDouble(), p.y.toDouble())
          if (dh != 0.0f) {
            gore.save(p, PIECE, p.h, dh)
            p.h += dh
          }
        }
      }
    } catch (t: Throwable) {
      gore.restore()
      ViewpointBloodFxFix.disable("raising severed parts onto the terrain", t)
    }
  }

  @Hook.Exit(className = "bloodfx.GoreGpu", methodName = "render")
  fun restoreGore() {
    gore.restore()
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
      for (i in count - 1 downTo 0) {
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
      }
      count = 0
      failure?.let { ViewpointBloodFxFix.disable("restoring the heights it raised", it) }
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
        ViewpointBloodFxFix.log {
          "%d frames, %d with terrain relief, %d particle draws raised by %.3f..%.3f".format(
            frames, withRelief, raised, lowest, highest,
          )
        }
        since = now
        frames = 0
        withRelief = 0
        raised = 0
      }
    }
  }
}
