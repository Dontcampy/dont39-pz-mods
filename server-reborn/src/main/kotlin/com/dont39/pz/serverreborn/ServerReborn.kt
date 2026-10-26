package com.dont39.pz.serverreborn

import com.dont39.pz.zbktutils.LoggerUtils.lzDebug
import com.dont39.pz.zbktutils.LoggerUtils.lzError
import com.dont39.pz.zbktutils.LoggerUtils.lzInfo
import com.dont39.pz.zbktutils.LoggerUtils.lzWarn
import zombie.debug.DebugType
import zombie.debug.LogSeverity

/** The mod's id and its log lines, each with the mod's prefix. */
internal object ServerReborn {
  const val MOD_ID = "Dont39ServerReborn"
  const val PREFIX = "[$MOD_ID] "

  inline fun log(message: () -> String) = DebugType.Mod.lzInfo { PREFIX + message() }

  inline fun warn(message: () -> String) = DebugType.Mod.lzWarn { PREFIX + message() }

  inline fun debug(message: () -> String) = DebugType.Mod.lzDebug { PREFIX + message() }

  inline fun error(message: () -> String) = DebugType.Mod.lzError { PREFIX + message() }

  fun error(message: String, t: Throwable) = DebugType.Mod.printException(t, PREFIX + message, LogSeverity.Error)
}
