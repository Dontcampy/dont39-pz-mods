package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjglx.opengl.Display
import zombie.core.Core

/**
 * Runs after the message pump, on the window's own thread, where IMM and window procedure calls belong. Restores the
 * window here too, so a failure elsewhere is undone on the next frame.
 */
internal object ImeWindow {
  private var hwnd = 0L
  private var placed = 0L

  @Hook.Exit(className = "org.lwjglx.opengl.Display", methodName = "processMessages")
  fun sync() {
    if (!ImeKeyboardFix.active()) {
      restore()
      return
    }
    try {
      // written on the main thread, so read once
      val box = Core.currentTextEntryBox
      val wanted = box != null && box.isEditable && box.isDoingTextEntry
      val window = GLFWNativeWin32.glfwGetWin32Window(Display.getWindow())
      check(window != 0L) { "GLFW has no Win32 window for the game window" }
      if (window != hwnd) {
        hwnd = window
        placed = 0L
      }
      ImeWindowProc.install(window)
      ImeContext.sync(window, wanted)
      val rect = ImeCaret.rect
      if (wanted && rect != 0L && rect != placed) {
        ImeContext.place(window, rect)
        placed = rect
      }
    } catch (t: Throwable) {
      restore()
      ImeKeyboardFix.disable("switching or placing the IME for the text box focus", t)
    }
  }

  /** From the window procedure, when a composition starts. */
  fun place(window: Long) {
    val rect = ImeCaret.rect
    if (rect != 0L) {
      ImeContext.place(window, rect)
      placed = rect
    }
  }

  private fun restore() {
    ImeWindowProc.restore()
    ImeContext.restore()
  }
}
