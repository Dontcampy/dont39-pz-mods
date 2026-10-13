package com.dont39.pz.imekeyboardfix

import org.lwjgl.system.JNI
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.windows.WindowsLibrary

/**
 * The IMM calls on the game window: attaches the IME only while a text box takes text entry, and places its windows at
 * the caret. IMM calls only work on the window's own thread.
 */
internal object ImeContext {
  // re-attaches the thread's default context, which keeps the player's CN/EN mode
  private const val IACE_DEFAULT = 0x10
  private const val DETACH = 0

  // a point, not GLFW's CFS_RECT: a caret-wide area would clip the system composition window
  private const val CFS_POINT = 0x2
  private const val CFS_EXCLUDE = 0x80

  // COMPOSITIONFORM, then CANDIDATEFORM
  private const val COMPOSITION_FORM = 28
  private const val CANDIDATE_FORM = 32

  private var associate = 0L
  private var getContext = 0L
  private var releaseContext = 0L
  private var setCompositionWindow = 0L
  private var setCandidateWindow = 0L
  private var forms = 0L

  private var hwnd = 0L
  private var attached = true

  fun resolve() {
    val imm = WindowsLibrary("imm32.dll")
    associate = function(imm, "ImmAssociateContextEx")
    getContext = function(imm, "ImmGetContext")
    releaseContext = function(imm, "ImmReleaseContext")
    setCompositionWindow = function(imm, "ImmSetCompositionWindow")
    setCandidateWindow = function(imm, "ImmSetCandidateWindow")
    forms = MemoryUtil.nmemCallocChecked(1, (COMPOSITION_FORM + CANDIDATE_FORM).toLong())
  }

  private fun function(imm: WindowsLibrary, name: String): Long {
    val address = imm.getFunctionAddress(name)
    check(address != 0L) { "${imm.path} has no $name" }
    return address
  }

  fun sync(window: Long, wanted: Boolean) {
    if (window != hwnd) {
      // a new window starts with the default context
      hwnd = window
      attached = true
    }
    if (wanted != attached) {
      check(JNI.callPPI(window, 0L, if (wanted) IACE_DEFAULT else DETACH, associate) != 0) {
        "ImmAssociateContextEx failed"
      }
      attached = wanted
    }
  }

  /** [rect] as packed by [ImeCaret]; the context must be attached. */
  fun place(window: Long, rect: Long) {
    val x = ImeCaret.x(rect)
    val y = ImeCaret.y(rect)
    val right = x + ImeCaret.width(rect)
    val bottom = y + ImeCaret.height(rect)
    val composition = forms
    MemoryUtil.memPutInt(composition, CFS_POINT)
    MemoryUtil.memPutInt(composition + 4, x)
    MemoryUtil.memPutInt(composition + 8, y)
    val candidate = forms + COMPOSITION_FORM
    MemoryUtil.memPutInt(candidate, 0)
    MemoryUtil.memPutInt(candidate + 4, CFS_EXCLUDE)
    MemoryUtil.memPutInt(candidate + 8, x)
    MemoryUtil.memPutInt(candidate + 12, y)
    MemoryUtil.memPutInt(candidate + 16, x)
    MemoryUtil.memPutInt(candidate + 20, y)
    MemoryUtil.memPutInt(candidate + 24, right)
    MemoryUtil.memPutInt(candidate + 28, bottom)
    val context = JNI.callPP(window, getContext)
    check(context != 0L) { "the game window has no input context" }
    try {
      check(JNI.callPPI(context, composition, setCompositionWindow) != 0) { "ImmSetCompositionWindow failed" }
      check(JNI.callPPI(context, candidate, setCandidateWindow) != 0) { "ImmSetCandidateWindow failed" }
    } finally {
      JNI.callPPI(window, context, releaseContext)
    }
  }

  fun restore() {
    try {
      if (!attached && hwnd != 0L) {
        JNI.callPPI(hwnd, 0L, IACE_DEFAULT, associate)
      }
    } catch (t: Throwable) {
    }
    attached = true
  }
}
