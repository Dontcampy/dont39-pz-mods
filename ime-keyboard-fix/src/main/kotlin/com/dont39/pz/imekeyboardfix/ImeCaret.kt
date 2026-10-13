package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import me.zed_0xff.zombie_buddy.Patch
import zombie.core.Core
import zombie.ui.TextManager
import zombie.ui.UITextBox2

/**
 * Where the focused text box draws its caret, in window client pixels. Computed after the box renders, when its scroll
 * offset is up to date for the frame, and published for the window thread.
 */
internal object ImeCaret {
  private const val MIN = -32768
  private const val MAX = 32767

  /** x, y, width, height as 16-bit fields, so the window thread never sees a torn rect; 0 until a box had focus. */
  @Volatile
  var rect = 0L
    private set

  // mirrors the caret UITextBox2.render draws
  @Hook.Exit(className = "zombie.ui.UITextBox2", methodName = "render", onThrowable = Patch.NoException::class)
  fun publish(@Patch.This box: UITextBox2) {
    if (!ImeKeyboardFix.active()) {
      return
    }
    try {
      if (box !== Core.currentTextEntryBox || !box.isEditable() || !box.isDoingTextEntry()) {
        return
      }
      val font = TextManager.instance.getFontFromEnum(box.font)
      val lineHeight = font.lineHeight
      val inset = box.inset
      val top = if (box.centerVertically) {
        ((box.getHeight() - TextManager.instance.MeasureStringY(box.font, box.internalText)) / 2.0).toInt()
      } else {
        inset
      }
      // render's own cursorLine is stale when it returned early, e.g. while hidden
      val line = box.toDisplayLine(box.cursorPos)
      val lines = box.lines
      var width = 0
      if (!lines.isEmpty()) {
        val text = lines[line]
        val offsets = box.textOffsetOfLineStart
        val column = (box.cursorPos - offsets[line]).coerceAtMost(text.length)
        width = font.getWidth(text, 0, column - 1, true)
        if (width > 0) {
          width--
        }
      }
      val left = -box.xOffset + inset + width
      val x = (left.toDouble() + box.absoluteX + box.xScroll).toInt()
      val y = ((top + line * lineHeight).toDouble() + box.absoluteY + box.yScroll).toInt()
      rect = pack(x, y, 1, lineHeight)
    } catch (t: Throwable) {
      ImeKeyboardFix.disable("locating the text caret for the IME windows", t)
    }
  }

  private fun pack(x: Int, y: Int, width: Int, height: Int): Long {
    check(x in MIN..MAX && y in MIN..MAX && height in 0..MAX) {
      "caret at $x,$y height $height does not fit the packed rect"
    }
    return ((x.toLong() and 0xFFFF) shl 48) or ((y.toLong() and 0xFFFF) shl 32) or
      ((width.toLong() and 0xFFFF) shl 16) or (height.toLong() and 0xFFFF)
  }

  fun x(rect: Long): Int = (rect shr 48).toShort().toInt()

  fun y(rect: Long): Int = (rect shr 32).toShort().toInt()

  fun width(rect: Long): Int = (rect shr 16).toInt() and 0xFFFF

  fun height(rect: Long): Int = rect.toInt() and 0xFFFF
}
