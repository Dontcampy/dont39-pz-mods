package com.dont39.pz.serverreborn

import com.pzlauncher.zabric.entrypoint.MainEntrypoint
import zombie.debug.DebugType

// not named Main: ZombieBuddy loads and initializes a class of that name in javaPkgName as its own entry point
object ServerRebornMain : MainEntrypoint {
  override fun onMain() {
    val version = ServerRebornMain::class.java.`package`.implementationVersion ?: "dev"
    DebugType.Mod.println("[Dont39ServerReborn] %s loaded", version)
  }
}
