package com.dont39.pz.modsync.client.rejoin

import com.dont39.pz.modsync.client.Worker
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
  /**
   * [JoinArgs.of] for the game thread, which never waits for DNS: done at once unless a host name must be resolved,
   * which a worker does. Its [log] may run on the worker.
   */
  class Lookup(args: List<String>, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit) {
    private val now: JoinArgs?
    private val worker: Worker<JoinArgs>?

    init {
      val found = scan(args)
      val targetHost = found.target?.let { targetHost(it, port, hosts) }
      val known = if (targetHost == null) false else compare(targetHost, host)
      if (known != null) {
        now = found.against(known)
        worker = null
      } else {
        now = null
        worker = Worker("Dont39AllInOne mod-sync +connect lookup") {
          found.against(resolved(checkNotNull(targetHost), host, port, hosts, log))
        }.start()
      }
    }

    /** Null while the lookup runs; throws what it threw. */
    val result: JoinArgs?
      get() = now ?: when (val o = checkNotNull(worker).outcome) {
        null -> null
        is Worker.Outcome.Done -> o.value
        is Worker.Outcome.Failed -> throw IllegalStateException("comparing this launch's +connect failed", o.error)
        Worker.Outcome.Cancelled -> throw IllegalStateException("the +connect lookup was cancelled")
      }
  }

  /** The last `+connect`'s value and the indices of every `+connect` and `+password` with their values. */
  private class Found(val target: String?, val joinArgs: Set<Int>) {
    fun against(toServer: Boolean) = when {
      target == null -> JoinArgs(null, false, emptySet())
      toServer -> JoinArgs(target, true, emptySet())
      else -> JoinArgs(target, false, joinArgs)
    }
  }

  companion object {
    private const val CONNECT = "+connect"
    private const val PASSWORD = "+password"

    // MainScreenState.main takes the next argument as the value of these, so it is never one of them itself
    private val TAKE_VALUE = setOf(CONNECT, PASSWORD, "-modfolders")

    private val IPV4 = Regex("""[0-9]{1,3}(\.[0-9]{1,3}){3}""")
    private val HEX = ('0'..'9') + ('a'..'f') + ('A'..'F')

    /**
     * [args]: this launch's arguments after the program; [host] and [port]: the server, as the join had them. JVM
     * options in [args] are scanned too: none can be one of these words, which the JVM would refuse. Resolves host
     * names on the calling thread; see [Lookup].
     */
    fun of(args: List<String>, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit): JoinArgs {
      val found = scan(args)
      val targetHost = found.target?.let { targetHost(it, port, hosts) } ?: return found.against(false)
      return found.against(compare(targetHost, host) ?: resolved(targetHost, host, port, hosts, log))
    }

    private fun scan(args: List<String>): Found {
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
      return Found(target, joinArgs)
    }

    /** Whether [targetHost] is [host], or null when only resolving a host name can tell. */
    private fun compare(targetHost: String, host: String): Boolean? {
      if (targetHost == host.trim()) {
        return true
      }
      val a = literal(targetHost)
      val b = literal(host.trim())
      return if (a != null && b != null) a == b else null
    }

    /** [target]'s host when it is one the main menu joins at [port], trimmed; null when it is not. */
    private fun targetHost(target: String, port: Int, hosts: Hosts): String? {
      // split as the main menu's Lua splits it (Java's String.split: no trailing empty parts), which joins two parts only
      val parts = target.split(':').dropLastWhile { it.isEmpty() }
      if (parts.size != 2) {
        return null
      }
      val targetHost = parts[0]
      if (parts[1].trim().toIntOrNull() != port || targetHost.isBlank() || hosts.isSteamId(targetHost)) {
        return null
      }
      return targetHost.trim()
    }

    private fun resolved(target: String, host: String, port: Int, hosts: Hosts, log: (() -> String) -> Unit): Boolean {
      fun addresses(name: String) = literal(name)?.let(::setOf) ?: try {
        hosts.resolve(name)
      } catch (e: UnknownHostException) {
        log { "cannot resolve $name to compare this launch's +connect $target:$port with $host:$port: $e" }
        emptySet()
      }
      return addresses(target).intersect(addresses(host.trim())).isNotEmpty()
    }

    /** [host] as an IP address, read without DNS; null when it is a name. */
    private fun literal(host: String): InetAddress? = when {
      IPV4.matches(host) -> host.split('.').map { it.toInt() }.takeIf { o -> o.all { it <= 255 } }
        ?.let { o -> InetAddress.getByAddress(ByteArray(4) { o[it].toByte() }) }
      // Java parses such a host as an IPv6 literal and throws when it is none, never looking it up; a non-ASCII digit
      // first is looked up
      ':' in host && (host[0] == ':' || host[0] in HEX) -> try {
        InetAddress.getByName(host)
      } catch (e: UnknownHostException) {
        null
      }
      else -> null
    }
  }
}
