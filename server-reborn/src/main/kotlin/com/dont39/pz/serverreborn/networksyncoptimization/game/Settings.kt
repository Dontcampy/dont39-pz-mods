package com.dont39.pz.serverreborn.networksyncoptimization.game

import com.dont39.pz.serverreborn.ServerReborn
import zombie.network.GameServer

/**
 * The launch options of network sync optimization (JVM system properties), read once at the main entrypoint: a summary
 * period for load tests and debug switches for the in-game tests. Off by default; a switch that is on says so once.
 */
internal object Settings {
  private const val SUMMARY = "serverreborn.summarySeconds"
  private const val SUMMARY_DEFAULT = 600
  private const val DROP_DELTA = "serverreborn.debugDropDelta"
  private const val FIELD_DIFF = "serverreborn.debugFieldDiff"
  private const val TOUCHES = "serverreborn.debugTouches"
  private const val DROP_MODULE = "serverreborn.debugDropModule"

  /** `key=value` summary lines every this many seconds, and at the end of a connection. */
  var summarySeconds = SUMMARY_DEFAULT
    private set

  /** Client: drops every n-th incoming container or item update, to test detection and repair; 0 when off. */
  var dropDelta = 0
    private set

  /**
   * Client: drops incoming server commands (Lua sendServerCommand) of this module, to test repairs of a mod's own sync;
   * every n-th by [dropDelta] when set, else every one. Null when off.
   */
  var dropModule: String? = null
    private set

  /** With [dropModule]: only this command of the module, or null for all of them. */
  var dropCommand: String? = null
    private set

  /** Client: logs which digest fields differ around each repair. Server: logs each snapshot item's values. */
  var fieldDiff = false
    private set

  /** Server: logs what happens to each in-scope update and content change, to find changes the checks miss. */
  var touches = false
    private set

  fun load() {
    summarySeconds = positive(SUMMARY, SUMMARY_DEFAULT)
    dropDelta = positive(DROP_DELTA, 0)
    fieldDiff = System.getProperty(FIELD_DIFF).toBoolean()
    touches = System.getProperty(TOUCHES).toBoolean()
    System.getProperty(DROP_MODULE)?.takeIf { it.isNotBlank() }?.let { module ->
      dropModule = module.substringBefore('.').trim()
      dropCommand = if ('.' in module) module.substringAfter('.').trim() else null
    }
    if (touches) {
      ServerReborn.warn {
        "debug: logs every container or item update it sends or receives and every container whose items changed " +
          "(-D$TOUCHES); leave it off for play"
      }
    }
    if (fieldDiff && GameServer.server) {
      ServerReborn.log { "debug: logs the digest values of each item it sends in a snapshot (-D$FIELD_DIFF)" }
    }
  }

  /** Logs the debug switches that are on; called once on a client. */
  fun reportClientSwitches() {
    if (dropDelta > 0) {
      ServerReborn.warn {
        "debug: drops every $dropDelta. incoming container or item update of the game (-D$DROP_DELTA), to test " +
          "repairs; leave it off for play"
      }
    }
    dropModule?.let { module ->
      val command = dropCommand?.let { ".$it" } ?: ""
      ServerReborn.warn {
        "debug: drops every ${maxOf(dropDelta, 1)}. incoming server command $module$command (-D$DROP_MODULE), to " +
          "test repairs; leave it off for play"
      }
    }
    if (fieldDiff) {
      ServerReborn.log { "debug: logs the digest fields that differ around each repair (-D$FIELD_DIFF)" }
    }
  }

  private fun positive(property: String, fallback: Int): Int {
    val value = System.getProperty(property) ?: return fallback
    value.trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    ServerReborn.warn { "-D$property=$value is not a whole number above 0; it stays $fallback" }
    return fallback
  }
}
