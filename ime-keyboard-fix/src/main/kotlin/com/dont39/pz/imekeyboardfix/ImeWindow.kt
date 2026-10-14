package com.dont39.pz.imekeyboardfix

import com.pzlauncher.zabric.hook.At
import com.pzlauncher.zabric.hook.CallbackInfo
import com.pzlauncher.zabric.hook.Hooks
import com.pzlauncher.zabric.hook.Inject
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjglx.opengl.Display
import zombie.core.Core

/**
 * Runs after the message pump, on the window's own thread, where IMM and window procedure calls belong. Restores the
 * window here too, so a failure elsewhere is undone on the next frame.
 */
@Hooks(Display::class)
internal object ImeWindow {
  private var hwnd = 0L
  private var placed = 0L

  // always: undoes the install once the feature is off, including after an error
  @Inject(method = ["processMessages"], at = [At("FINALLY")], always = true)
  fun sync(ci: CallbackInfo) {
    if (!ImeKeyboardFix.active()) {
      restore()
      return
    }
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
  }

  /** From the window procedure, when a composition starts. */
  fun place(window: Long) {
    val rect = ImeCaret.rect
    if (rect != 0L) {
      ImeContext.place(window, rect)
      placed = rect
    }
  }

  // both, even when the first fails: each undoes its own install
  private fun restore() {
    val first = runCatching { ImeWindowProc.restore() }.exceptionOrNull()
    val second = runCatching { ImeContext.restore() }.exceptionOrNull()
    if (first != null) {
      second?.let(first::addSuppressed)
      throw first
    }
    second?.let { throw it }
  }
}
