package com.dont39.pz.zombiebuddykotlinutils

import zombie.core.Core
import zombie.debug.DebugType
import zombie.debug.LogSeverity

/** The game's logger checks the severity only after the message is built; these build it only when it is written. */
object LoggerUtils {
  inline fun DebugType.lzInfo(message: () -> String) {
    if (isEnabled(LogSeverity.General)) {
      println(message())
    }
  }

  /** `debugln` also drops everything outside `-debug` mode. */
  inline fun DebugType.lzDebug(message: () -> String) {
    if (Core.debug && isEnabled(LogSeverity.Debug)) {
      debugln(message())
    }
  }

  inline fun DebugType.lzWarn(message: () -> String) {
    if (isEnabled(LogSeverity.Warning)) {
      warn(message())
    }
  }
}
