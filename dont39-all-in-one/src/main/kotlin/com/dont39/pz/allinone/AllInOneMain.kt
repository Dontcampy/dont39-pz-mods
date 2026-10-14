package com.dont39.pz.allinone

import com.dont39.pz.imekeyboardfix.ImeKeyboardFix
import com.dont39.pz.zbktutils.reportWidening
import com.pzlauncher.zabric.entrypoint.MainEntrypoint
import zombie.debug.DebugType

// not named Main: ZombieBuddy loads and initializes a class of that name in javaPkgName as its own entry point
object AllInOneMain : MainEntrypoint {
  private const val PREFIX = "[Dont39AllInOne] "

  override fun onMain() {
    val version = AllInOneMain::class.java.`package`.implementationVersion ?: "dev"
    DebugType.Mod.println("$PREFIX%s loaded", version)
    reportWidening("Dont39AllInOne", AllInOneMain::class.java)
    ImeKeyboardFix.init()
  }
}
