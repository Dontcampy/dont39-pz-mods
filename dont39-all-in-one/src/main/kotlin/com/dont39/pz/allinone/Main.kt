package com.dont39.pz.allinone

import com.dont39.pz.imekeyboardfix.ImeKeyboardFix
import com.dont39.pz.viewpointbloodfxfix.ViewpointBloodFxFix
import com.dont39.pz.zbkt.ZombieBuddyKotlin
import com.dont39.pz.zbktutils.reportWidening
import zombie.debug.DebugType

object Main {
  private const val PREFIX = "[Dont39AllInOne] "

  @JvmStatic
  fun main(args: Array<String>) {
    // without the current Kotlin this runs on the game's 1.4.10; ZombieBuddyKotlin has logged why
    if (!ZombieBuddyKotlin.isReady()) return
    val version = Main::class.java.`package`.implementationVersion ?: "dev"
    DebugType.Mod.println("$PREFIX%s loaded", version)
    reportWidening("Dont39AllInOne", Main::class.java)
    ViewpointBloodFxFix.init()
    ImeKeyboardFix.init()
  }
}
