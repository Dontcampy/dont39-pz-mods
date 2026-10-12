package com.dont39.pz.imekeyboardfix

import com.dont39.pz.zombiebuddykotlinutils.Feature
import com.dont39.pz.zombiebuddykotlinutils.Resolver
import org.lwjgl.system.Platform
import zombie.ui.UITextEntryInterface
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier

/**
 * With an IME switched on, keys go into its composition instead of the game, so movement and hotkeys stop working. The
 * IME is detached from the game window except while a text box takes text entry.
 */
object ImeKeyboardFix : Feature("ime-keyboard-fix", "Dont39AllInOne") {
  override fun problem(): String? = if (Platform.get() == Platform.WINDOWS) {
    null
  } else {
    "the game is not running on Windows; it only handles Windows input methods"
  }

  override fun resolve() {
    ImeContext.resolve()
    // the hook target and the text box focus; a game update that renames them must not leave the fix silently unused
    Resolver.static(Resolver.type("org.lwjglx.opengl.Display"), "processMessages", MethodType.methodType(Void.TYPE))
    val box = Resolver.field(Resolver.type("zombie.core.Core"), "currentTextEntryBox")
    if (!Modifier.isStatic(box.modifiers) || box.type != UITextEntryInterface::class.java) {
      throw IllegalStateException("$box is not a static ${UITextEntryInterface::class.java.name}")
    }
  }

  override fun resolveContext(): String =
    "looking up the Windows IME function and the game's window and text box members"
}
