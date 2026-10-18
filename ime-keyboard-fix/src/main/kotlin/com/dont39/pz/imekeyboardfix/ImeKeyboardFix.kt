package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zbktutils.Feature
import org.lwjgl.system.Platform
import zombie.network.GameServer

/**
 * With an IME switched on, keys go into its composition instead of the game, so movement and hotkeys stop working. The
 * IME is detached from the game window except while a text box takes text entry, and there its composition and
 * candidate windows, which GLFW hides, are shown at the caret.
 */
object ImeKeyboardFix : Feature("ime-keyboard-fix", "Dont39AllInOne", widened = true) {
  override fun problem(): String? = when {
    GameServer.server -> "this is a dedicated server, which has no game window"
    Platform.get() != Platform.WINDOWS -> "the game is not running on Windows; it only handles Windows input methods"
    else -> null
  }

  override fun resolve() {
    ImeContext.resolve()
  }

  override fun resolveContext(): String = "looking up the Windows IME functions"
}
