package com.dont39.pz.allinone

import com.dont39.pz.viewpointbloodfxfix.ViewpointBloodFxFix
import zombie.debug.DebugType

object Main {
  private const val PREFIX = "[Dont39AllInOne] "

  @JvmStatic
  fun main(args: Array<String>) {
    val version = Main::class.java.`package`.implementationVersion ?: "dev"
    DebugType.Mod.println("$PREFIX$version loaded")
    ViewpointBloodFxFix.init()
  }
}
