package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.Feature
import com.dont39.pz.zombiebuddykotlinutils.Resolver
import org.lwjgl.system.Platform
import zombie.ui.UITextEntryInterface
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier

/**
 * With an IME switched on, keys go into its composition instead of the game, so movement and hotkeys stop working. The
 * IME is detached from the game window except while a text box takes text entry, and there its composition and
 * candidate windows, which GLFW hides, are shown at the caret.
 */
object ImeKeyboardFix : Feature("ime-keyboard-fix", "Dont39AllInOne") {
  override fun problem(): String? = if (Platform.get() == Platform.WINDOWS) {
    null
  } else {
    "the game is not running on Windows; it only handles Windows input methods"
  }

  override fun resolve() {
    ImeContext.resolve()
    // the hook targets and what they read; a game update that changes them must not leave the fix silently unused
    Resolver.static(Resolver.type("org.lwjglx.opengl.Display"), "processMessages", MethodType.methodType(Void.TYPE))
    val box = Resolver.field(Resolver.type("zombie.core.Core"), "currentTextEntryBox")
    if (!Modifier.isStatic(box.modifiers) || box.type != UITextEntryInterface::class.java) {
      throw IllegalStateException("$box is not a static ${UITextEntryInterface::class.java.name}")
    }
    ImeCaret.resolve()
  }

  override fun resolveContext(): String =
    "looking up the Windows IME functions and the game's window, text box focus and text caret members"
}
