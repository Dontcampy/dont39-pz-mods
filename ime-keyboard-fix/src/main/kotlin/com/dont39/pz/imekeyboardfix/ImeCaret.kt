package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.Resolver
import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import gnu.trove.list.array.TIntArrayList
import me.zed_0xff.zombie_buddy.Patch
import zombie.core.Core
import zombie.ui.TextManager
import zombie.ui.UIFont
import zombie.ui.UITextBox2
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Stack

/**
 * Where the focused text box draws its caret, in window client pixels. Computed after the box renders, when its scroll
 * offset is up to date for the frame, and published for the window thread.
 */
internal object ImeCaret {
  private const val MIN = -32768
  private const val MAX = 32767

  private lateinit var xOffset: Field
  private lateinit var centerVertically: Field
  private lateinit var textOffsetOfLineStart: Field

  /** x, y, width, height as 16-bit fields, so the window thread never sees a torn rect; 0 until a box had focus. */
  @Volatile
  var rect = 0L
    private set

  fun resolve() {
    val box = Resolver.type("zombie.ui.UITextBox2")
    Resolver.virtual(box, "render", MethodType.methodType(Void.TYPE))
    xOffset = field(box, "xOffset", Integer.TYPE)
    centerVertically = field(box, "centerVertically", java.lang.Boolean.TYPE)
    textOffsetOfLineStart = field(box, "textOffsetOfLineStart", TIntArrayList::class.java)
    field(box, "lines", Stack::class.java)
    field(box, "font", UIFont::class.java)
  }

  private fun field(owner: Class<*>, name: String, type: Class<*>): Field {
    val field = Resolver.field(owner, name)
    if (Modifier.isStatic(field.modifiers) || field.type != type) {
      throw IllegalStateException("$field is not an instance ${type.name}")
    }
    return field
  }

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
      val lineHeight = font.getLineHeight()
      val inset = box.getInset()
      var top = inset
      if (centerVertically.getBoolean(box)) {
        top = ((box.getHeight() - TextManager.instance.MeasureStringY(box.font, box.internalText)) / 2.0).toInt()
      }
      // render's own cursorLine is stale when it returned early, e.g. while hidden
      val line = box.toDisplayLine(box.getCursorPos())
      val lines = box.lines
      var width = 0
      if (!lines.isEmpty()) {
        val text = lines.get(line)
        val offsets = textOffsetOfLineStart.get(box) as TIntArrayList
        val column = Math.min(box.getCursorPos() - offsets.get(line), text.length)
        width = font.getWidth(text, 0, column - 1, true)
        if (width > 0) {
          width--
        }
      }
      val left = -xOffset.getInt(box) + inset + width
      val x = (left.toDouble() + box.getAbsoluteX() + box.getXScroll()).toInt()
      val y = ((top + line * lineHeight).toDouble() + box.getAbsoluteY() + box.getYScroll()).toInt()
      rect = pack(x, y, 1, lineHeight)
    } catch (t: Throwable) {
      ImeKeyboardFix.disable("locating the text caret for the IME windows", t)
    }
  }

  private fun pack(x: Int, y: Int, width: Int, height: Int): Long {
    if (x < MIN || x > MAX || y < MIN || y > MAX || height < 0 || height > MAX) {
      throw IllegalStateException("caret at $x,$y height $height does not fit the packed rect")
    }
    return ((x.toLong() and 0xFFFF) shl 48) or ((y.toLong() and 0xFFFF) shl 32) or
      ((width.toLong() and 0xFFFF) shl 16) or (height.toLong() and 0xFFFF)
  }

  fun x(rect: Long): Int = (rect shr 48).toShort().toInt()

  fun y(rect: Long): Int = (rect shr 32).toShort().toInt()

  fun width(rect: Long): Int = (rect shr 16).toInt() and 0xFFFF

  fun height(rect: Long): Int = rect.toInt() and 0xFFFF
}
