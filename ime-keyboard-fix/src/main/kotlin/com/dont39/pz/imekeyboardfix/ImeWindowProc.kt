package com.dont39.pz.imekeyboardfix

import org.lwjgl.BufferUtils
import org.lwjgl.system.windows.User32
import org.lwjgl.system.windows.WindowProc

/**
 * Subclasses the game window's procedure so the system IME windows show. GLFW hides the composition window and keeps
 * the IME messages that update both windows from `DefWindowProc`, since it expects the game to draw them itself.
 */
internal object ImeWindowProc {
  private const val GWLP_WNDPROC = -4

  private const val WM_IME_STARTCOMPOSITION = 0x010D
  private const val WM_IME_ENDCOMPOSITION = 0x010E
  private const val WM_IME_COMPOSITION = 0x010F
  private const val WM_IME_SETCONTEXT = 0x0281
  private const val WM_IME_NOTIFY = 0x0282
  private const val WM_IME_CHAR = 0x0286

  private const val GCS_COMPSTR = 0x0008L
  private const val GCS_RESULTSTR = 0x0800L

  private const val IMN_CHANGECANDIDATE = 0x0003L
  private const val IMN_CLOSECANDIDATE = 0x0004L
  private const val IMN_OPENCANDIDATE = 0x0005L
  private const val IMN_SETOPENSTATUS = 0x0008L

  // never freed: once installed, Windows may call it for as long as the window lives
  private var proc: WindowProc? = null
  private var hwnd = 0L
  private var original = 0L

  private class Proc : WindowProc() {
    override fun invoke(hwnd: Long, uMsg: Int, wParam: Long, lParam: Long): Long =
      ImeWindowProc.handle(hwnd, uMsg, wParam, lParam)
  }

  fun install(window: Long) {
    if (window == hwnd) {
      return
    }
    restore()
    val ours = proc ?: Proc().also { proc = it }
    val error = BufferUtils.createIntBuffer(1)
    val previous = User32.SetWindowLongPtr(error, window, GWLP_WNDPROC, ours.address())
    check(previous != 0L) { "SetWindowLongPtr failed with error ${error[0]}" }
    original = previous
    hwnd = window
  }

  fun restore() {
    val window = hwnd
    if (window == 0L) {
      return
    }
    // cleared first: a failure is reported once, not retried every frame
    hwnd = 0L
    // a later subclass keeps calling ours, which then forwards everything to the original
    if (User32.GetWindowLongPtr(window, GWLP_WNDPROC) != proc!!.address()) {
      return
    }
    val error = BufferUtils.createIntBuffer(1)
    // the previous value is ours, never 0, so 0 is a failure whatever the last error says
    val previous = User32.SetWindowLongPtr(error, window, GWLP_WNDPROC, original)
    check(previous != 0L) { "SetWindowLongPtr failed with error ${error[0]}" }
  }

  fun handle(window: Long, message: Int, wParam: Long, lParam: Long): Long {
    if (ImeKeyboardFix.active()) {
      try {
        when (message) {
          // GLFW only strips the composition window flag here
          WM_IME_SETCONTEXT -> return User32.DefWindowProc(window, message, wParam, lParam)
          // GLFW has committed the result already; DefWindowProc would turn it into a second WM_CHAR
          WM_IME_CHAR -> return 0L
          // when the context was attached in the meantime, the earlier placement did not reach it
          WM_IME_STARTCOMPOSITION -> ImeWindow.place(window)
        }
      } catch (t: Throwable) {
        ImeKeyboardFix.disable("handling the IME messages of the game window", t)
      }
    }
    // outside the try: GLFW's own errors surface as without this feature
    val result = User32.nCallWindowProc(original, window, message, wParam, lParam)
    if (!ImeKeyboardFix.active() || !keptFromSystem(message, wParam, lParam)) {
      return result
    }
    return try {
      User32.DefWindowProc(window, message, wParam, lParam)
    } catch (t: Throwable) {
      ImeKeyboardFix.disable("handling the IME messages of the game window", t)
      result
    }
  }

  // where GLFW returns without DefWindowProc
  private fun keptFromSystem(message: Int, wParam: Long, lParam: Long): Boolean = when (message) {
    WM_IME_COMPOSITION -> (lParam and (GCS_RESULTSTR or GCS_COMPSTR)) != 0L
    WM_IME_ENDCOMPOSITION -> true
    WM_IME_NOTIFY -> wParam == IMN_SETOPENSTATUS || wParam == IMN_OPENCANDIDATE ||
      wParam == IMN_CHANGECANDIDATE || wParam == IMN_CLOSECANDIDATE
    else -> false
  }
}
