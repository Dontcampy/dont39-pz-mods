package com.dont39.pz.modsync.client.rejoin

import java.net.InetAddress
import java.net.UnknownHostException
import zombie.core.znet.SteamUtils

/** How the main menu tells a target's host apart; the game's in [GameHosts], tests pass their own. */
internal interface Hosts {
  /** Whether the main menu joins [host] as a co-op host's Steam ID. */
  fun isSteamId(host: String): Boolean

  /** Throws when [host] cannot be resolved. */
  fun resolve(host: String): Set<InetAddress>
}

internal object GameHosts : Hosts {
  override fun isSteamId(host: String) = SteamUtils.isValidSteamID(host)

  override fun resolve(host: String) = InetAddress.getAllByName(host).toSet()
}

/**
 * The join this launch's arguments ask the main menu for, against the server a join stopped for a restart: [target]
 * is the last `+connect`'s value (null without one), [toServer] whether it points at that server, and [omitted] the
 * indices a restart leaves out, so that it does not join another server than the one it restarts for.
 */
internal data class JoinArgs(val target: String?, val toServer: Boolean, val omitted: Set<Int>) {
  companion object {
    private const val CONNECT = "+connect"
    private const val PASSWORD = "+password"

    // MainScreenState.main takes the next argument as the value of these, so it is never one of them itself
    private val TAKE_VALUE = setOf(CONNECT, PASSWORD, "-modfolders")

    /**
     * [args]: this launch's arguments after the program; [host] and [port]: the server, as the join had them. JVM
     * options in [args] are scanned too: none can be one of these words, which the JVM would refuse.
     */
    fun of(args: List<String>, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit): JoinArgs {
      var target: String? = null
      val joinArgs = mutableSetOf<Int>()
      var i = 0
      while (i < args.size) {
        val arg = args[i]
        if (arg == CONNECT || arg == PASSWORD) {
          joinArgs += i
          if (i + 1 < args.size) {
            joinArgs += i + 1
            if (arg == CONNECT) {
              target = args[i + 1]
            }
          }
        }
        if (arg in TAKE_VALUE) {
          i++
        }
        i++
      }
      if (target == null) {
        return JoinArgs(null, false, emptySet())
      }
      val toServer = pointsAt(target, host, port, hosts, log)
      return JoinArgs(target, toServer, if (toServer) emptySet() else joinArgs)
    }

    private fun pointsAt(target: String, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit): Boolean {
      // split as the main menu's Lua splits it (Java's String.split: no trailing empty parts), which joins two parts only
      val parts = target.split(':').dropLastWhile { it.isEmpty() }
      if (parts.size != 2) {
        return false
      }
      val targetHost = parts[0]
      if (parts[1].trim().toIntOrNull() != port || targetHost.isBlank() || hosts.isSteamId(targetHost)) {
        return false
      }
      if (targetHost.trim() == host.trim()) {
        return true
      }
      fun addresses(name: String) = try {
        hosts.resolve(name.trim())
      } catch (e: UnknownHostException) {
        log { "cannot resolve $name to compare this launch's +connect $target with $host:$port: $e" }
        emptySet()
      }
      return addresses(targetHost).intersect(addresses(host)).isNotEmpty()
    }
  }
}
