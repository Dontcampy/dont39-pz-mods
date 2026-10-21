package com.dont39.pz.modsync.client.rejoin

import com.dont39.pz.modsync.client.java.Launch
import java.io.IOException

/**
 * Restarts the game for a join to [host]:[port] that stopped for a restart. Null once the game quits; otherwise why it
 * cannot restart, and the game runs on. A refusal is no error of this feature: the player can still quit and start the
 * game themself.
 */
internal fun restartFor(launch: Launch, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit): String? {
  val args = JoinArgs.of(launch.arguments(), host, port, hosts, log)
  when {
    args.target == null -> {}
    args.toServer -> log { "the restarted game keeps +connect ${args.target}, which is this server" }
    else -> log { "the restarted game leaves out +connect ${args.target} and +password: another server than this one" }
  }
  return try {
    launch.relaunch(args.omitted)
    null
  } catch (e: Exception) {
    // Zabric's refusals; anything else (an index it has no argument for, the quit failing) is an error
    if (e !is IOException && e !is IllegalStateException) throw e
    log { "the game cannot restart itself: $e" + (e.cause?.let { ", caused by $it" } ?: "") }
    e.message ?: e.toString()
  }
}
