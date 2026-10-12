package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.glue.Hook
import org.lwjgl.glfw.GLFWNativeWin32
import org.lwjgl.system.JNI
import org.lwjgl.system.windows.WindowsLibrary
import org.lwjglx.opengl.Display
import zombie.core.Core

/**
 * Attaches the IME to the game window only while a text box takes text entry. Runs after the message pump because IMM
 * association calls only work on the window's own thread.
 */
internal object ImeContext {
  // re-attaches the thread's default context, which keeps the player's CN/EN mode
  private const val IACE_DEFAULT = 0x10
  private const val DETACH = 0

  private var associate = 0L
  private var hwnd = 0L
  private var attached = true

  fun resolve() {
    val imm = WindowsLibrary("imm32.dll")
    val address = imm.getFunctionAddress("ImmAssociateContextEx")
    if (address == 0L) {
      throw IllegalStateException("${imm.path} has no ImmAssociateContextEx")
    }
    associate = address
  }

  @Hook.Exit(className = "org.lwjglx.opengl.Display", methodName = "processMessages")
  fun sync() {
    if (!ImeKeyboardFix.active()) {
      return
    }
    try {
      // written on the main thread, so read once
      val box = Core.currentTextEntryBox
      val wanted = box != null && box.isEditable && box.isDoingTextEntry
      val window = GLFWNativeWin32.glfwGetWin32Window(Display.getWindow())
      if (window == 0L) {
        throw IllegalStateException("GLFW has no Win32 window for the game window")
      }
      if (window != hwnd) {
        // a new window starts with the default context
        hwnd = window
        attached = true
      }
      if (wanted != attached) {
        if (JNI.callPPI(window, 0L, if (wanted) IACE_DEFAULT else DETACH, associate) == 0) {
          throw IllegalStateException("ImmAssociateContextEx failed")
        }
        attached = wanted
      }
    } catch (t: Throwable) {
      restore()
      ImeKeyboardFix.disable("switching the IME for the text box focus", t)
    }
  }

  private fun restore() {
    try {
      if (!attached && hwnd != 0L) {
        JNI.callPPI(hwnd, 0L, IACE_DEFAULT, associate)
      }
    } catch (t: Throwable) {
    }
    attached = true
  }
}
