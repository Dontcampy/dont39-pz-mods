package com.dont39.pz.allinone

import com.dont39.pz.viewpointbloodfxfix.ViewpointBloodFxFix
import com.dont39.pz.zombiebuddykotlin.ZombieBuddyKotlin
import zombie.debug.DebugType

object Main {
  private const val PREFIX = "[Dont39AllInOne] "

  @JvmStatic
  fun main(args: Array<String>) {
    val version = Main::class.java.`package`.implementationVersion ?: "dev"
    DebugType.Mod.println("$PREFIX$version loaded")
    val problem = kotlinProblem()
    if (problem != null) {
      val line: Any = "${PREFIX}ZombieBuddyKotlin is not ready: $problem" // warn(Object); warn(String, ...) formats
      DebugType.Mod.warn(line)
      // the features are Kotlin: without init() they stay inactive, and their patches return at once
      return
    }
    ViewpointBloodFxFix.init()
  }

  /**
   * Null when the Kotlin runtime is ready. Only the standard library parts that the game's own Kotlin 1.4.10 also has
   * may run before this check.
   */
  private fun kotlinProblem(): String? = try {
    if (ZombieBuddyKotlin.isReady()) null else ZombieBuddyKotlin.problem()
  } catch (e: LinkageError) {
    // the library jar is not on the classpath: not enabled, or loaded after this mod
    "it is not loaded (enable ZombieBuddyKotlin and load it before Dont39 All-in-One)"
  }
}
